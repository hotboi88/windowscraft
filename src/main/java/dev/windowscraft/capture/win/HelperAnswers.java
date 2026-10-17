package dev.windowscraft.capture.win;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Matches the in-game monitor helper's answers to the questions asked. It answers in the order
 * asked — except a few slow ones it answers from their own thread, whenever they're done (opening
 * another copy, resetting a monitor's apps): one line each, in between the
 * others' answers. Matched in order, such a line ended the wrong question and left the next one
 * waiting for an answer that had already gone: 20 s later the helper was restarted, every
 * in-game monitor going dark with it.
 *
 * A question given up on (it took too long) keeps its place: its answer, when it comes, is taken
 * and dropped, so it can't end up with the questions after it.
 */
final class HelperAnswers {
    /** First words of the answers that come whenever they're done. */
    static final Set<String> WHENEVER = Set.of("boxlaunched", "boxreset");

    private static final class Pending {
        final String what;
        final Set<String> ends;
        final List<String> lines = new ArrayList<>();
        final CompletableFuture<List<String>> done;
        final long asked = System.nanoTime();
        boolean givenUp;

        Pending(String what, Set<String> ends, CompletableFuture<List<String>> done) {
            this.what = what;
            this.ends = ends;
            this.done = done;
        }

        boolean whenever() { return WHENEVER.containsAll(ends); }
    }

    private final Deque<Pending> pending = new ArrayDeque<>();
    private final Consumer<String> note;

    HelperAnswers() { this(s -> {}); }

    /** {@code note}: told when a question given up on is answered after all (how long it took). */
    HelperAnswers(Consumer<String> note) { this.note = note; }

    /** A question just asked ({@code what}: its name, for the log): its answer ends with a line starting with one of those words. */
    synchronized CompletableFuture<List<String>> expect(String what, String... ends) {
        CompletableFuture<List<String>> f = new CompletableFuture<>();
        pending.add(new Pending(what, Set.of(ends), f));
        return f;
    }

    /** That question never reached the helper: nothing will come for it. */
    synchronized void forget(CompletableFuture<List<String>> f) {
        pending.removeIf(p -> p.done == f);
    }

    /** Nobody waits for that answer any more; it's still expected (and dropped when it comes). */
    synchronized void giveUp(CompletableFuture<List<String>> f) {
        for (Pending p : pending) if (p.done == f) p.givenUp = true;
    }

    /** Is the answer to that question still to come? */
    synchronized boolean waiting(CompletableFuture<List<String>> f) {
        for (Pending p : pending) if (p.done == f) return true;
        return false;
    }

    /** What's still to come, for the log: "windows (2 lines so far, 31 s)". */
    synchronized String describe() {
        List<String> out = new ArrayList<>();
        long now = System.nanoTime();
        for (Pending p : pending) out.add(p.what + " (" + p.lines.size() + " lines so far, " + (now - p.asked) / 1_000_000_000L + " s)");
        return out.isEmpty() ? "nothing" : String.join(", ", out);
    }

    /** A line of an answer (not an event). */
    void line(String line) {
        String first = line.split(" ", 2)[0];
        Pending done = null;
        synchronized (this) {
            boolean whenever = WHENEVER.contains(first);
            for (Pending p : pending) {
                // (one of those goes to its own question wherever it is; everything else to the
                // oldest question still waiting that's answered in order)
                if (whenever ? p.whenever() && p.ends.contains(first) : !p.whenever()) {
                    p.lines.add(line);
                    if (p.ends.contains(first)) {
                        done = p;
                        pending.remove(p);
                    }
                    break;
                }
            }
        }
        if (done == null) return;
        if (done.givenUp) note.accept(done.what + " was answered after all, " + (System.nanoTime() - done.asked) / 1_000_000L + " ms after it was asked");
        else done.done.complete(done.lines);
    }

    /** The helper stopped: nothing it owed will come. */
    void failAll(IOException why) {
        List<Pending> left;
        synchronized (this) {
            left = new ArrayList<>(pending);
            pending.clear();
        }
        for (Pending p : left) p.done.completeExceptionally(why);
    }
}
