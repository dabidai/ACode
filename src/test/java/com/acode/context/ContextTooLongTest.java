package com.acode.context;

import com.acode.provider.InvalidRequestException;
import com.acode.provider.ProviderException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ch07 T5：ContextTooLong 按异常文案短语判定。 */
class ContextTooLongTest {

    @Test
    void anthropicPhraseMatches() {
        assertTrue(ContextTooLong.matches(new InvalidRequestException("prompt is too long: ...")));
        assertTrue(ContextTooLong.matches(new ProviderException("messages: prompt is too long and maximum context length is ...")));
    }

    @Test
    void openAiPhraseMatches() {
        assertTrue(ContextTooLong.matches(new InvalidRequestException("This model's maximum context length is 128000 tokens.")));
    }

    @Test
    void ordinaryErrorsDoNotMatch() {
        assertFalse(ContextTooLong.matches(new InvalidRequestException("参数错误")));
        assertFalse(ContextTooLong.matches(new ProviderException("rate limited")));
    }

    @Test
    void nullMessageDoesNotThrow() {
        ProviderException e = new InvalidRequestException(null);
        assertFalse(ContextTooLong.matches(e));
        assertFalse(ContextTooLong.matches(null));
    }
}
