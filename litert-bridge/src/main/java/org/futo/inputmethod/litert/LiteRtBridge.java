package org.futo.inputmethod.litert;

import com.google.ai.edge.litertlm.Backend;
import com.google.ai.edge.litertlm.Contents;
import com.google.ai.edge.litertlm.Conversation;
import com.google.ai.edge.litertlm.ConversationConfig;
import com.google.ai.edge.litertlm.Engine;
import com.google.ai.edge.litertlm.EngineConfig;
import com.google.ai.edge.litertlm.Message;
import com.google.ai.edge.litertlm.MessageCallback;
import com.google.ai.edge.litertlm.SamplerConfig;
import com.google.ai.edge.litertlm.ThinkingConfig;
import com.google.ai.edge.litertlm.ToolProvider;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Blocking, Java-friendly access to a local LiteRT-LM model.
 *
 * Not thread-safe: create, use and close an instance from a single thread. {@link #generate}
 * blocks until the model is done and can be cancelled by interrupting the calling thread.
 */
public final class LiteRtBridge implements AutoCloseable {

    /** Receives text as the model produces it. Called from a LiteRT-LM thread. */
    public interface Listener {
        void onText(String chunk);
    }

    private final Engine engine;

    /** Loads the model. Slow (seconds); never call on the main thread. */
    public LiteRtBridge(String modelPath, String cacheDir) {
        // CPU backend, no vision or audio backend, no token or image limit.
        EngineConfig config = new EngineConfig(
                modelPath, new Backend.CPU(null, null), null, null, null, null, cacheDir);
        Engine created = new Engine(config);
        try {
            created.initialize();
        } catch (RuntimeException | Error e) {
            // close() throws if initialize() failed, so do not close here.
            throw e;
        }
        engine = created;
    }

    /** Generation was stopped because it went past the output limit (the model was rambling). */
    public static final class OutputTooLongException extends RuntimeException {
        public OutputTooLongException() { super("The model's answer was too long."); }
    }

    /** The model did not finish in time. */
    public static final class TimedOutException extends RuntimeException {
        public TimedOutException() { super("The model took too long."); }
    }

    // How long to wait for the model to stop after asking it to cancel.
    private static final long CANCEL_WAIT_MILLIS = 3000;

    /**
     * True once a generation could not be stopped. The native side may still be busy, so this
     * instance must not be used or closed again; create a new one.
     */
    private volatile boolean stuck = false;

    public boolean isStuck() {
        return stuck;
    }

    /**
     * Runs one prompt in a fresh conversation (no history) and returns the full response text.
     *
     * @param maxChars      stop and throw {@link OutputTooLongException} past this many characters
     * @param timeoutMillis stop and throw {@link TimedOutException} after this long
     * @throws InterruptedException if the calling thread is interrupted; generation is cancelled
     * @throws RuntimeException     if the model reports an error
     */
    public String generate(
            String systemPrompt,
            String userPrompt,
            int topK,
            double topP,
            double temperature,
            int maxChars,
            long timeoutMillis,
            final Listener listener) throws InterruptedException {
        if (stuck) throw new IllegalStateException("A previous generation could not be stopped.");

        // Rewrites are short and direct, so "thinking" (Qwen3's reasoning phase) is switched off;
        // it made each rewrite take several times longer for no visible benefit.
        // Arguments between the sampler and the thinking config are LiteRT-LM's own defaults
        // (read from the 0.19.1 library); Java has no way to skip them.
        ConversationConfig config = new ConversationConfig(
                Contents.Companion.of(systemPrompt),
                Collections.<Message>emptyList(),
                Collections.<ToolProvider>emptyList(),
                new SamplerConfig(topK, topP, temperature, 0),
                true,
                null,
                Collections.<String, Object>emptyMap(),
                null,
                false,
                null,
                new ThinkingConfig(false));

        final Conversation conversation = engine.createConversation(config);
        boolean canClose = true;
        try {
            final StringBuffer text = new StringBuffer();
            final CountDownLatch done = new CountDownLatch(1);
            final AtomicReference<Throwable> failure = new AtomicReference<>();
            final AtomicBoolean tooLong = new AtomicBoolean(false);

            conversation.sendMessageAsync(userPrompt, new MessageCallback() {
                @Override
                public void onMessage(Message message) {
                    if (tooLong.get()) return;
                    String chunk = message.toString();
                    text.append(chunk);
                    // cancelProcess() is called from the waiting thread below, not from this
                    // native callback thread.
                    if (text.length() > maxChars) {
                        tooLong.set(true);
                        return;
                    }
                    if (listener != null) listener.onText(chunk);
                }

                @Override
                public void onDone() {
                    done.countDown();
                }

                @Override
                public void onError(Throwable throwable) {
                    failure.set(throwable);
                    done.countDown();
                }
            });

            final long deadline = System.currentTimeMillis() + timeoutMillis;
            try {
                while (!done.await(100, TimeUnit.MILLISECONDS)) {
                    if (tooLong.get() || System.currentTimeMillis() > deadline) break;
                }
            } catch (InterruptedException e) {
                canClose = stop(conversation, done);
                throw e;
            }

            if (done.getCount() > 0) {
                canClose = stop(conversation, done);
                if (tooLong.get()) throw new OutputTooLongException();
                throw new TimedOutException();
            }
            if (tooLong.get()) throw new OutputTooLongException();

            Throwable error = failure.get();
            if (error != null) {
                throw new RuntimeException(String.valueOf(error.getMessage()), error);
            }
            return text.toString();
        } finally {
            // Closing a conversation the native side is still using could crash, so a stuck one
            // is left alone.
            if (canClose) conversation.close();
        }
    }

    /** Cancels generation and waits briefly for it to stop. Returns false if it did not stop. */
    private boolean stop(Conversation conversation, CountDownLatch done) {
        conversation.cancelProcess();
        boolean interrupted = false;
        boolean stopped = false;
        final long until = System.currentTimeMillis() + CANCEL_WAIT_MILLIS;
        while (true) {
            final long left = until - System.currentTimeMillis();
            if (left <= 0) break;
            try {
                stopped = done.await(left, TimeUnit.MILLISECONDS);
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
        if (!stopped) stuck = true;
        return stopped;
    }

    @Override
    public void close() {
        // The native side may still be running a generation that could not be stopped.
        if (stuck) return;
        engine.close();
    }
}
