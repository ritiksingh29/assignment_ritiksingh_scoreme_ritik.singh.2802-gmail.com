import java.util.*;

public class main {

    // Helper class to hold result data
    public static class AssignmentResult {
        public boolean feasible;
        public Map<String, Integer> assignment;
        public double penalty;
        public long runtimeMs;
        public String violationReason;
    }

    // Task class to hold attributes and the conflict graph edges (enemy list)
    public static class Task {
        String idStr;
        int index;
        double[] req; // [CPU, RAM, GPU, Net]
        int winLo;
        int winHi;
        double weight;
        Set<Integer> conflicts;
        double riskScore;

        public Task(String idStr, int index, double[] req, int winLo, int winHi, double weight) {
            this.idStr = idStr;
            this.index = index;
            this.req = req;
            this.winLo = winLo;
            this.winHi = winHi;
            this.weight = weight;
            this.conflicts = new HashSet<>();
            // Risk Calculation: High weight & tight window = higher risk of failing
            this.riskScore = this.weight / (this.winHi - this.winLo + 1.0);
        }
    }

    private int n;
    private int K;
    private double lambdaWeight;
    private double[][] capacities; // K x 4 matrix
    private List<Task> tasks;

    public ScoreMeScheduler(int n, int K, double lambdaWeight, double[][] capacities, List<Task> tasks, List<int[]> conflictEdges) {
        this.n = n;
        this.K = K;
        this.lambdaWeight = lambdaWeight;
        this.capacities = capacities;
        this.tasks = tasks;

        // Build adjacency list for conflicts (The "Enemy List")
        for (int[] edge : conflictEdges) {
            this.tasks.get(edge[0]).conflicts.add(edge[1]);
            this.tasks.get(edge[1]).conflicts.add(edge[0]);
        }
    }

    // Calculates the variance of resource usage to punish overloaded slots
    private double calculateImbalancePenalty(double[][] slotUsage) {
        double penalty = 0.0;
        for (int d = 0; d < 4; d++) {
            // Calculate mean usage for dimension d
            double totalD = 0.0;
            for (int s = 0; s < K; s++) {
                totalD += slotUsage[s][d];
            }
            double meanD = totalD / K;

            // Sum squared deviations normalized by capacity
            for (int s = 0; s < K; s++) {
                double cap = capacities[s][d];
                if (cap > 0) {
                    penalty += Math.pow((slotUsage[s][d] - meanD) / cap, 2);
                }
            }
        }
        return this.lambdaWeight * penalty;
    }

    public AssignmentResult solve() {
        long startTime = System.nanoTime(); 

        // Sort tasks descending by Risk Score
        List<Task> sortedTasks = new ArrayList<>(this.tasks);
        sortedTasks.sort((t1, t2) -> Double.compare(t2.riskScore, t1.riskScore));

        Map<String, Integer> assignment = new HashMap<>();
        double[][] slotUsage = new double[K][4]; // Tracks used CPU/RAM/GPU/Net
        
        // Tracks assigned task indices per slot for fast conflict checking
        List<Set<Integer>> slotOccupants = new ArrayList<>(K);
        for (int s = 0; s < K; s++) {
            slotOccupants.add(new HashSet<>());
        }

        for (Task t : sortedTasks) {
            List<Integer> validSlots = new ArrayList<>();

            // F3 Check: Only iterate within SLA bounds
            for (int s = t.winLo; s <= t.winHi; s++) {
                
                // F1 Check: Fast Set intersection to find conflicts
                if (!Collections.disjoint(t.conflicts, slotOccupants.get(s))) {
                    continue; // Enemy found in this slot. Skip it.
                }

                // F2 Check: Ensure all 4 dimensions fit
                boolean fits = true;
                for (int d = 0; d < 4; d++) {
                    if (slotUsage[s][d] + t.req[d] > capacities[s][d]) {
                        fits = false;
                        break; // Resource overflow. Skip it.
                    }
                }

                if (fits) {
                    validSlots.add(s);
                }
            }

            // Feasibility Trap: If it fits nowhere, halt immediately
            if (validSlots.isEmpty()) {
                AssignmentResult res = new AssignmentResult();
                res.feasible = false;
                res.violationReason = "Task " + t.idStr + " failed to find a valid slot due to conflicts/capacity within its SLA " + t.winLo + "-" + t.winHi + ".";
                res.runtimeMs = (System.nanoTime() - startTime) / 1_000_000;
                return res;
            }

            // Simulate placing the task to find the lowest penalty
            int bestSlot = -1;
            double minPenalty = Double.MAX_VALUE;

            for (int s : validSlots) {
                // Copy current usage arrays for simulation
                double[][] simulatedUsage = new double[K][4];
                for (int i = 0; i < K; i++) {
                    System.arraycopy(slotUsage[i], 0, simulatedUsage[i], 0, 4);
                }
                
                // Add the task's resources to the simulated slot
                for (int d = 0; d < 4; d++) {
                    simulatedUsage[s][d] += t.req[d];
                }

                double delayCost = t.weight * s;
                double imbCost = calculateImbalancePenalty(simulatedUsage);
                double totalCost = delayCost + imbCost;

                if (totalCost < minPenalty) {
                    minPenalty = totalCost;
                    bestSlot = s;
                }
            }

            // Lock in the best assignment and update trackers
            assignment.put(t.idStr, bestSlot);
            slotOccupants.get(bestSlot).add(t.index);
            for (int d = 0; d < 4; d++) {
                slotUsage[bestSlot][d] += t.req[d];
            }
        }

        // Calculate final true penalty for the whole schedule
        double finalDelayPenalty = 0.0;
        for (Task t : this.tasks) {
            finalDelayPenalty += t.weight * assignment.get(t.idStr);
        }
        double finalPenalty = finalDelayPenalty + calculateImbalancePenalty(slotUsage);

        AssignmentResult res = new AssignmentResult();
        res.feasible = true;
        res.assignment = assignment;
        res.penalty = finalPenalty;
        res.runtimeMs = (System.nanoTime() - startTime) / 1_000_000;
        return res;
    }
}
