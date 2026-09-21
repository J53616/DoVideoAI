package com.example.server.dto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AnalysisTaskMsgTraceTest {

    @Test
    void carriesTraceIdentityAcrossMqSerializationBoundary() {
        TraceContext expected = new TraceContext(
                "trace-1", "task-1", 7L, 9L, AnalysisTaskMsg.START_ANALYSIS, 1234L);

        AnalysisTaskMsg message = new AnalysisTaskMsg(
                7L, AnalysisTaskMsg.START_ANALYSIS, "hash", "goal", "GENERAL", expected);

        assertEquals(expected, message.traceContext());
    }

    @Test
    void acceptsLegacyMessagesWithoutTraceFields() {
        AnalysisTaskMsg message = new AnalysisTaskMsg(
                7L, AnalysisTaskMsg.START_ANALYSIS, "hash", "goal", "GENERAL");

        assertNull(message.traceContext());
    }
}
