package lhqm.furimeo.wisper.jobs;

import java.util.List;

/**
 * One page of failing jobs, worst first.
 *
 * <p>"Worst first" is by failure count and then by most recent failure, not by execution
 * time. A job on its fourteenth attempt is scheduled furthest into the future precisely
 * because it keeps failing, so ordering by when it will next run puts the worst problem
 * at the bottom of the list.
 */
public record FailedJobPage(List<FailedJob> jobs, long total, int offset, int pageSize) {

    public FailedJobPage {
        jobs = jobs == null ? List.of() : List.copyOf(jobs);
    }

    /** Nothing is failing, which is the normal state and deserves its own message. */
    public boolean isEmpty() {
        return jobs.isEmpty() && offset == 0;
    }

    public boolean hasPrevious() {
        return offset > 0;
    }

    public boolean hasNext() {
        return offset + jobs.size() < total;
    }
}
