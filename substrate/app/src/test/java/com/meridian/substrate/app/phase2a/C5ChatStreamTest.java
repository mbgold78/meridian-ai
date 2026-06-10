package com.meridian.substrate.app.phase2a;

import com.meridian.substrate.app.core.service.ChatService;
import com.meridian.substrate.common.ai.CompletionChunk;
import com.meridian.substrate.common.domain.ChatInteraction;
import com.meridian.substrate.common.repo.ChatInteractionRepository;
import com.meridian.substrate.engine.ai.cost.CostSummary;
import com.meridian.substrate.engine.ai.cost.CostTracker;
import com.meridian.substrate.engine.ai.persona.PersonaContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * C5 acceptance — streaming chat (SSE) wiring against the mock provider.
 *
 * <p>NOT {@code @Transactional}: stream completion runs on a Reactor scheduler
 * thread (the mock uses {@code delayElements}), so the {@code ChatInteraction}
 * persist commits in its own transaction. Created rows are cleaned up per
 * domainId in {@link #cleanUp()}.
 *
 * <p>Covers the three C5 streaming obligations from the C3 handoff notes:
 * usage from the terminal chunk is recorded with {@link CostTracker}, the
 * interaction is persisted with truthful {@code refusalDetected}, and the SSE
 * endpoint starts an async stream that runs to completion.
 */
@SpringBootTest(properties = "substrate.llm.provider=mock")
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class C5ChatStreamTest {

    @Autowired ChatService chatService;
    @Autowired ChatInteractionRepository chatInteractionRepository;
    @Autowired CostTracker costTracker;
    @Autowired MockMvc mvc;

    private final List<UUID> usedDomainIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (UUID domainId : usedDomainIds) {
            chatInteractionRepository.deleteAll(
                    chatInteractionRepository.findByDomainIdOrderByCreatedAtDesc(domainId));
        }
        usedDomainIds.clear();
    }

    private UUID newDomainId() {
        UUID domainId = UUID.randomUUID();
        usedDomainIds.add(domainId);
        return domainId;
    }

    @Test
    void chatStreamEmitsChunksRecordsCostAndPersistsTruthfulInteraction() {
        UUID domainId = newDomainId();
        CostSummary before = costTracker.getSummary();

        List<CompletionChunk> chunks = chatService
                .chatStream(domainId, "stream me something", new PersonaContext("L3_ANALYST", "tester"))
                .collectList()
                .block(Duration.ofSeconds(10));

        assertThat(chunks).isNotNull().hasSize(3);
        String assembled = chunks.stream()
                .map(CompletionChunk::delta)
                .filter(delta -> delta != null)
                .reduce("", String::concat);
        assertThat(assembled).isEqualTo("This is a mock streaming response from MERIDIAN.");

        CompletionChunk terminal = chunks.get(chunks.size() - 1);
        assertThat(terminal.stopReason()).isEqualTo("end_turn");
        assertThat(terminal.usage()).isNotNull();

        // doOnComplete ran before block() returned: cost recorded from the
        // terminal chunk's aggregate usage (S12) ...
        CostSummary after = costTracker.getSummary();
        assertThat(after.totalCalls()).isGreaterThan(before.totalCalls());
        assertThat(after.totalInputTokens()).isEqualTo(before.totalInputTokens() + 25);
        assertThat(after.totalOutputTokens()).isEqualTo(before.totalOutputTokens() + 18);

        // ... and the interaction persisted with truthful refusal + usage columns
        List<ChatInteraction> interactions =
                chatInteractionRepository.findByDomainIdOrderByCreatedAtDesc(domainId);
        assertThat(interactions).hasSize(1);
        ChatInteraction interaction = interactions.get(0);
        assertThat(interaction.getAssistantMessage())
                .isEqualTo("This is a mock streaming response from MERIDIAN.");
        assertThat(interaction.getRefusalDetected()).isFalse();
        assertThat(interaction.getInputTokens()).isEqualTo(25);
        assertThat(interaction.getOutputTokens()).isEqualTo(18);
        assertThat(interaction.getCostEstimate()).isGreaterThan(0.0);
    }

    @Test
    void sseEndpointStartsAsyncStreamAndDrivesItToCompletion() throws Exception {
        UUID domainId = newDomainId();

        mvc.perform(get("/api/v1/chat/stream")
                        .param("domainId", domainId.toString())
                        .param("message", "hello over SSE")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted());

        // The mock flux completes ~300ms after subscription on a Reactor thread;
        // the persisted interaction proves the SSE handler subscribed and the
        // stream ran to completion (including the completion-side bookkeeping).
        ChatInteraction persisted = awaitInteraction(domainId, Duration.ofSeconds(10));
        assertThat(persisted.getAssistantMessage())
                .isEqualTo("This is a mock streaming response from MERIDIAN.");
        assertThat(persisted.getRefusalDetected()).isFalse();
    }

    private ChatInteraction awaitInteraction(UUID domainId, Duration timeout) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            List<ChatInteraction> rows =
                    chatInteractionRepository.findByDomainIdOrderByCreatedAtDesc(domainId);
            if (!rows.isEmpty()) {
                return rows.get(0);
            }
            Thread.sleep(50);
        }
        throw new AssertionError("ChatInteraction for domain " + domainId
                + " was not persisted within " + timeout);
    }
}
