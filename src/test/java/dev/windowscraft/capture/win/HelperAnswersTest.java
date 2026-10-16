package dev.windowscraft.capture.win;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class HelperAnswersTest {
    @Test
    void inOrderAnswersGoToTheirQuestions() {
        HelperAnswers a = new HelperAnswers();
        CompletableFuture<List<String>> modes = a.expect("modes-end");
        CompletableFuture<List<String>> seat = a.expect("seated", "fail");
        a.line("mode 1920 1080 60");
        a.line("modes-end");
        a.line("seated");
        assertEquals(List.of("mode 1920 1080 60", "modes-end"), modes.join());
        assertEquals(List.of("seated"), seat.join());
    }

    /** Opening a copy answered after the menu's next questions: the helper was restarted 20 s later. */
    @Test
    void aSlowAnswerInBetweenEndsOnlyItsOwnQuestion() {
        HelperAnswers a = new HelperAnswers();
        CompletableFuture<List<String>> open = a.expect("boxlaunched");
        CompletableFuture<List<String>> wins = a.expect("windows-end");
        CompletableFuture<List<String>> modes = a.expect("modes-end");
        a.line("win 1 2 0 Discord");
        a.line("windows-end");
        a.line("boxlaunched ok");
        a.line("mode 1800 1200 60");
        a.line("modes-end");
        assertEquals(List.of("win 1 2 0 Discord", "windows-end"), wins.join());
        assertEquals(List.of("boxlaunched ok"), open.join());
        assertEquals(List.of("mode 1800 1200 60", "modes-end"), modes.join());
    }

    @Test
    void aSlowAnswerInTheMiddleOfAnotherLeavesItWhole() {
        HelperAnswers a = new HelperAnswers();
        CompletableFuture<List<String>> reset = a.expect("boxreset");
        CompletableFuture<List<String>> wins = a.expect("windows-end");
        a.line("win 1 2 0 A");
        a.line("boxreset ok");
        a.line("win 3 4 0 B");
        a.line("windows-end");
        assertEquals(List.of("boxreset ok"), reset.join());
        assertEquals(List.of("win 1 2 0 A", "win 3 4 0 B", "windows-end"), wins.join());
    }

    @Test
    void twoOfTheSameSlowKindAreBothAnswered() {
        HelperAnswers a = new HelperAnswers();
        CompletableFuture<List<String>> first = a.expect("boxlaunched");
        CompletableFuture<List<String>> second = a.expect("boxlaunched");
        CompletableFuture<List<String>> modes = a.expect("modes-end");
        a.line("modes-end");
        a.line("boxlaunched ok");
        a.line("boxlaunched failed");
        assertTrue(modes.isDone() && first.isDone() && second.isDone());
    }

    @Test
    void aForgottenQuestionNoLongerTakesAnswers() {
        HelperAnswers a = new HelperAnswers();
        CompletableFuture<List<String>> slow = a.expect("windows-end");
        CompletableFuture<List<String>> next = a.expect("modes-end");
        a.forget(slow);
        a.line("modes-end");
        assertFalse(slow.isDone());
        assertEquals(List.of("modes-end"), next.join());
    }

    @Test
    void aSlowAnswerNobodyWaitsForIsDropped() {
        HelperAnswers a = new HelperAnswers();
        CompletableFuture<List<String>> modes = a.expect("modes-end");
        a.line("boxlaunched ok");
        a.line("modes-end");
        assertEquals(List.of("modes-end"), modes.join());
    }

    @Test
    void aStoppedHelperFailsEveryQuestion() {
        HelperAnswers a = new HelperAnswers();
        CompletableFuture<List<String>> x = a.expect("windows-end");
        CompletableFuture<List<String>> y = a.expect("boxlaunched");
        a.failAll(new IOException("stopped"));
        assertTrue(x.isCompletedExceptionally() && y.isCompletedExceptionally());
    }
}
