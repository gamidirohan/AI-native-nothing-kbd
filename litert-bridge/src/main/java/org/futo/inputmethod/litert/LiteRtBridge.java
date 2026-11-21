package org.futo.inputmethod.litert;

import com.google.ai.edge.litertlm.ActivationDataType;
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

    /** How to run the model. Defaults match what the app has always used. */
    public static final class Options {
        /** CPU threads, or 0 for LiteRT-LM's default. Ignored on the GPU. */
        public int cpuThreads = 0;
        /** Run on the GPU (OpenCL) instead of the CPU. */
        public boolean gpu = false;
        /** "float32", "float16", "int16", "int8", or null for LiteRT-LM's default. */
        public String activation = null;
        /** Maximum tokens (prompt + answer) per conversation, or 0 for the model's own limit. */
        public int maxTokens = 0;

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Options)) return false;
            Options other = (Options) o;
            return cpuThreads == other.cpuThreads && gpu == other.gpu && maxTokens == other.maxTokens
                    && (activation == null ? other.activation == null : activation.equals(other.activation));
        }

        @Override
        public int hashCode() {
            return (cpuThreads * 31 + (gpu ? 1 : 0)) * 31 + maxTokens
                    + (activation == null ? 0 : activation.hashCode());
        }

        @Override
        public String toString() {
            return (gpu ? "gpu" : "cpu threads=" + cpuThreads) + " activation=" + activation
                    + " maxTokens=" + maxTokens;
        }
    }

    /** Loads the model with the default options. Slow (seconds); never call on the main thread. */
    public LiteRtBridge(String modelPath, String cacheDir) {
        this(modelPath, cacheDir, new Options());
    }

    /** Loads the model. Slow (seconds); never call on the main thread. */
    public LiteRtBridge(String modelPath, String cacheDir, Options options) {
        // No vision or audio backend, no image limit.
        Integer threads = options.cpuThreads > 0 ? options.cpuThreads : null;
        Backend backend = options.gpu ? new Backend.GPU() : new Backend.CPU(threads, null);
        Integer maxTokens = options.maxTokens > 0 ? options.maxTokens : null;
        EngineConfig config = new EngineConfig(
                modelPath, backend, null, null, maxTokens, null, cacheDir,
                activationType(options.activation));
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

    private static ActivationDataType activationType(String name) {
        if (name == null) return null;
        switch (name) {
            case "float32": return ActivationDataType.FLOAT32;
            case "float16": return ActivationDataType.FLOAT16;
            case "int16": return ActivationDataType.INT16;
            case "int8": return ActivationDataType.INT8;
            default: return null;
        }
    }

    @Override
    public void close() {
        // The native side may still be running a generation that could not be stopped.
        if (stuck) return;
        engine.close();
    }
}
