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
import com.google.ai.edge.litertlm.ToolProvider;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;
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
        // Arguments after modelPath are LiteRT-LM's own Kotlin defaults (CPU backend, no vision
        // or audio backend, no token or image limit); Java cannot call Kotlin default arguments.
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

    /**
     * Runs one prompt in a fresh conversation (no history) and returns the full response text.
     *
     * @throws InterruptedException if the calling thread is interrupted; generation is cancelled
     * @throws RuntimeException     if the model reports an error
     */
    public String generate(
            String systemPrompt,
            String userPrompt,
            int topK,
            double topP,
            double temperature,
            final Listener listener) throws InterruptedException {

        // Only the system instruction and sampler are set; everything else is LiteRT-LM's own
        // Kotlin default (empty history and tools, no lora, no thinking config).
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
                null,
                false);

        final Conversation conversation = engine.createConversation(config);
        try {
            final StringBuffer text = new StringBuffer();
            final CountDownLatch done = new CountDownLatch(1);
            final AtomicReference<Throwable> failure = new AtomicReference<>();

            conversation.sendMessageAsync(userPrompt, new MessageCallback() {
                @Override
                public void onMessage(Message message) {
                    String chunk = message.toString();
                    text.append(chunk);
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

            try {
                done.await();
            } catch (InterruptedException e) {
                conversation.cancelProcess();
                // Wait for the callback to finish so the conversation is idle before closing it.
                awaitUninterruptibly(done);
                throw e;
            }

            Throwable error = failure.get();
            if (error != null) {
                throw new RuntimeException(String.valueOf(error.getMessage()), error);
            }
            return text.toString();
        } finally {
            conversation.close();
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    @Override
    public void close() {
        engine.close();
    }
}
