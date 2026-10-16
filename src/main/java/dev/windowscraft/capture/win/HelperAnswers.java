package dev.windowscraft.capture.win;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Matches the in-game monitor helper's answers to the questions asked. It answers in the order
 * asked — except a few slow ones it answers from their own thread, whenever they're done (opening
 * another copy, resetting a monitor's apps, its own browser): one line each, in between the
 * others' answers. Matched in order, such a line ended the wrong question and left the next one
 * waiting for an answer that had already gone: 20 s later the helper was restarted, every
 * in-game monitor going dark with it.
 */
final class HelperAnswers {
    /** First words of the answers that come whenever they're done. */
    static final Set<String> WHENEVER = Set.of("boxlaunched", "boxreset", "ownbrowser");

    private record Pending(Set<String> ends, List<String> lines, CompletableFuture<List<String>> done) {
        boolean whenever() { return WHENEVER.containsAll(ends); }
    }

    private final Deque<Pending> pending = new ArrayDeque<>();

    /** A question just asked: its answer ends with a line starting with one of those words. */
    synchronized CompletableFuture<List<String>> expect(String... ends) {
        CompletableFuture<List<String>> f = new CompletableFuture<>();
        pending.add(new Pending(Set.of(ends), new ArrayList<>(), f));
        return f;
    }

    /** Stops waiting for that one (it took too long). */
    synchronized void forget(CompletableFuture<List<String>> f) {
        pending.removeIf(p -> p.done == f);
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
        if (done != null) done.done.complete(done.lines);
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
