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
        CompletableFuture<List<String>> modes = a.expect("q", "modes-end");
        CompletableFuture<List<String>> seat = a.expect("q", "seated", "fail");
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
        CompletableFuture<List<String>> open = a.expect("q", "boxlaunched");
        CompletableFuture<List<String>> wins = a.expect("q", "windows-end");
        CompletableFuture<List<String>> modes = a.expect("q", "modes-end");
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
        CompletableFuture<List<String>> reset = a.expect("q", "boxreset");
        CompletableFuture<List<String>> wins = a.expect("q", "windows-end");
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
        CompletableFuture<List<String>> first = a.expect("q", "boxlaunched");
        CompletableFuture<List<String>> second = a.expect("q", "boxlaunched");
        CompletableFuture<List<String>> modes = a.expect("q", "modes-end");
        a.line("modes-end");
        a.line("boxlaunched ok");
        a.line("boxlaunched failed");
        assertTrue(modes.isDone() && first.isDone() && second.isDone());
    }

    @Test
    void aQuestionThatNeverReachedTheHelperTakesNoAnswers() {
        HelperAnswers a = new HelperAnswers();
        CompletableFuture<List<String>> lost = a.expect("q", "windows-end");
        CompletableFuture<List<String>> next = a.expect("q", "modes-end");
        a.forget(lost);
        a.line("modes-end");
        assertFalse(lost.isDone());
        assertEquals(List.of("modes-end"), next.join());
    }

    /** A slow helper (after a resolution change): its late answer can't end up with the next question. */
    @Test
    void aLateAnswerToAQuestionGivenUpOnIsDropped() {
        java.util.List<String> notes = new java.util.ArrayList<>();
        HelperAnswers a = new HelperAnswers(notes::add);
        CompletableFuture<List<String>> slow = a.expect("windows", "windows-end");
        CompletableFuture<List<String>> next = a.expect("rects", "rects-end");
        slow.completeExceptionally(new IOException("busy"));
        a.giveUp(slow);
        assertTrue(a.waiting(slow));
        assertTrue(a.describe().startsWith("windows (0 lines so far"), a.describe());
        a.line("win 1 2 0 A");
        a.line("windows-end");
        assertFalse(a.waiting(slow));
        assertEquals(1, notes.size());
        assertTrue(notes.get(0).startsWith("windows was answered after all"), notes.get(0));
        assertFalse(next.isDone());
        a.line("rect 0 x 0 0 10 10");
        a.line("rects-end");
        assertEquals(List.of("rect 0 x 0 0 10 10", "rects-end"), next.join());
    }

    @Test
    void aSlowAnswerNobodyWaitsForIsDropped() {
        HelperAnswers a = new HelperAnswers();
        CompletableFuture<List<String>> modes = a.expect("q", "modes-end");
        a.line("boxlaunched ok");
        a.line("modes-end");
        assertEquals(List.of("modes-end"), modes.join());
    }

    @Test
    void aStoppedHelperFailsEveryQuestion() {
        HelperAnswers a = new HelperAnswers();
        CompletableFuture<List<String>> x = a.expect("q", "windows-end");
        CompletableFuture<List<String>> y = a.expect("q", "boxlaunched");
        a.failAll(new IOException("stopped"));
        assertTrue(x.isCompletedExceptionally() && y.isCompletedExceptionally());
    }
}
