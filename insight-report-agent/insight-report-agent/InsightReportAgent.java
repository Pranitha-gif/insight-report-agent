import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * insight-report-agent
 * --------------------
 * A simple rule-based "agent" that looks at a CSV file, PLANS which analysis
 * steps make sense, EXECUTES them one by one, and WRITES a short findings report.
 *
 * Agent loop:   inspect data -> plan steps -> run steps -> summarize -> write report
 *
 * No external libraries and no LLM calls, so it runs anywhere with Java 17+.
 * The planner is where an LLM could be plugged in later.
 *
 * Usage:
 *   java InsightReportAgent.java --demo                 (creates sales.csv, then analyzes it)
 *   java InsightReportAgent.java data.csv               (analyze your own file)
 *   java InsightReportAgent.java data.csv --out my.md   (choose report file)
 *
 * Or compile first:
 *   javac InsightReportAgent.java && java InsightReportAgent --demo
 */
public class InsightReportAgent {

    // ------------------------------------------------------------------
    // Core types
    // ------------------------------------------------------------------
    /** One insight. Higher priority (1-3) means more important for the summary. */
    record Finding(String step, int priority, String headline, String detail) {}

    /** One analysis step the agent can plan and run. */
    interface Step {
        String name();

        List<Finding> run(Dataset data);
    }

    // ------------------------------------------------------------------
    // Dataset: tiny CSV reader plus column type detection
    // ------------------------------------------------------------------
    static class Dataset {
        final List<String> columns;
        final List<Map<String, String>> rows;

        Dataset(List<String> columns, List<Map<String, String>> rows) {
            this.columns = columns;
            this.rows = rows;
        }

        static Dataset fromCsv(Path path) throws IOException {
            List<String> lines = Files.readAllLines(path);
            if (lines.isEmpty()) {
                throw new IOException("CSV file is empty: " + path);
            }
            List<String> header = parseLine(lines.get(0)).stream().map(String::trim).toList();
            List<Map<String, String>> rows = new ArrayList<>();
            for (int i = 1; i < lines.size(); i++) {
                if (lines.get(i).isBlank()) {
                    continue;
                }
                List<String> cells = parseLine(lines.get(i));
                Map<String, String> row = new LinkedHashMap<>();
                for (int c = 0; c < header.size(); c++) {
                    row.put(header.get(c), c < cells.size() ? cells.get(c).trim() : "");
                }
                rows.add(row);
            }
            return new Dataset(header, rows);
        }

        /** Splits one CSV line, honoring double quotes. */
        static List<String> parseLine(String line) {
            List<String> out = new ArrayList<>();
            StringBuilder cur = new StringBuilder();
            boolean inQuotes = false;
            for (int i = 0; i < line.length(); i++) {
                char ch = line.charAt(i);
                if (ch == '"') {
                    if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQuotes = !inQuotes;
                    }
                } else if (ch == ',' && !inQuotes) {
                    out.add(cur.toString());
                    cur.setLength(0);
                } else {
                    cur.append(ch);
                }
            }
            out.add(cur.toString());
            return out;
        }

        List<String> values(String col) {
            return rows.stream()
                    .map(r -> r.get(col))
                    .filter(v -> v != null && !v.isBlank())
                    .toList();
        }

        boolean isDate(String col) {
            List<String> v = values(col);
            return !v.isEmpty() && v.stream().allMatch(s -> s.matches("\\d{4}-\\d{2}-\\d{2}.*"));
        }

        boolean isNumeric(String col) {
            List<String> v = values(col);
            return !v.isEmpty() && v.stream().allMatch(Dataset::isNumber);
        }

        static boolean isNumber(String s) {
            try {
                double d = Double.parseDouble(s);
                return !Double.isNaN(d) && !Double.isInfinite(d);
            } catch (NumberFormatException e) {
                return false;
            }
        }

        double number(Map<String, String> row, String col) {
            String s = row.get(col);
            if (s == null || !isNumber(s)) {
                return Double.NaN;
            }
            return Double.parseDouble(s);
        }

        double[] numbers(String col) {
            return rows.stream().mapToDouble(r -> number(r, col)).filter(d -> !Double.isNaN(d)).toArray();
        }
    }

    // ------------------------------------------------------------------
    // Small math/format helpers
    // ------------------------------------------------------------------
    static double mean(double[] a) {
        return Arrays.stream(a).average().orElse(Double.NaN);
    }

    /** Linear-interpolated quantile on a SORTED array. */
    static double quantile(double[] sorted, double q) {
        double pos = q * (sorted.length - 1);
        int lo = (int) Math.floor(pos);
        int hi = (int) Math.ceil(pos);
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo);
    }

    static String fmt(double v) {
        return String.format(Locale.US, "%,.2f", v);
    }

    static String pct(double v) {
        return String.format(Locale.US, "%.1f%%", v);
    }

    // ------------------------------------------------------------------
    // Analysis steps
    // ------------------------------------------------------------------
    /** Row/column counts and missing values. */
    static class ProfileStep implements Step {
        public String name() {
            return "Profile dataset";
        }

        public List<Finding> run(Dataset d) {
            List<Finding> out = new ArrayList<>();
            out.add(new Finding(name(), 1,
                    d.rows.size() + " rows and " + d.columns.size() + " columns",
                    "Columns: " + String.join(", ", d.columns)));

            List<String> missing = new ArrayList<>();
            for (String c : d.columns) {
                long blanks = d.rows.stream().filter(r -> r.get(c) == null || r.get(c).isBlank()).count();
                if (blanks > 0) {
                    missing.add(c + " (" + blanks + ")");
                }
            }
            if (missing.isEmpty()) {
                out.add(new Finding(name(), 1, "No missing values", "Every column is fully populated."));
            } else {
                out.add(new Finding(name(), 2, "Missing values in " + missing.size() + " column(s)",
                        String.join(", ", missing)));
            }
            return out;
        }
    }

    /** Average, median, min, max and total of a numeric column. */
    static class SummaryStatsStep implements Step {
        private final String col;

        SummaryStatsStep(String col) {
            this.col = col;
        }

        public String name() {
            return "Summary stats for " + col;
        }

        public List<Finding> run(Dataset d) {
            double[] v = d.numbers(col);
            if (v.length == 0) {
                return List.of();
            }
            double[] s = v.clone();
            Arrays.sort(s);
            double median = quantile(s, 0.5);
            double avg = mean(v);
            return List.of(new Finding(name(), 1,
                    col + ": average " + fmt(avg) + ", median " + fmt(median),
                    "Min " + fmt(s[0]) + ", max " + fmt(s[s.length - 1]) + ", total " + fmt(Arrays.stream(v).sum())));
        }
    }

    /** Which categories contribute most to a measure. */
    static class TopContributorsStep implements Step {
        private final String category;
        private final String measure;

        TopContributorsStep(String category, String measure) {
            this.category = category;
            this.measure = measure;
        }

        public String name() {
            return "Top " + category + " by " + measure;
        }

        public List<Finding> run(Dataset d) {
            Map<String, Double> sums = new HashMap<>();
            for (Map<String, String> row : d.rows) {
                String key = row.get(category);
                double v = d.number(row, measure);
                if (key == null || key.isBlank() || Double.isNaN(v)) {
                    continue;
                }
                sums.merge(key, v, Double::sum);
            }
            double total = sums.values().stream().mapToDouble(Double::doubleValue).sum();
            if (sums.isEmpty() || total == 0) {
                return List.of();
            }

            List<Map.Entry<String, Double>> sorted = new ArrayList<>(sums.entrySet());
            sorted.sort(Map.Entry.<String, Double>comparingByValue().reversed());
            List<Map.Entry<String, Double>> top = sorted.subList(0, Math.min(3, sorted.size()));
            Map.Entry<String, Double> last = sorted.get(sorted.size() - 1);

            String headline = category + " leaders by " + measure + ": "
                    + top.stream()
                    .map(e -> e.getKey() + " (" + pct(e.getValue() / total * 100) + ")")
                    .collect(Collectors.joining(", "));
            String detail = "Lowest: " + last.getKey() + " (" + pct(last.getValue() / total * 100)
                    + "). Total " + measure + ": " + fmt(total) + ".";
            int priority = top.get(0).getValue() / total >= 0.40 ? 3 : 2;
            return List.of(new Finding(name(), priority, headline, detail));
        }
    }

    /** Month-over-month movement of a measure. */
    static class TrendStep implements Step {
        private final String dateCol;
        private final String measure;

        TrendStep(String dateCol, String measure) {
            this.dateCol = dateCol;
            this.measure = measure;
        }

        public String name() {
            return "Monthly trend of " + measure;
        }

        public List<Finding> run(Dataset d) {
            TreeMap<String, Double> byMonth = new TreeMap<>();
            for (Map<String, String> row : d.rows) {
                String date = row.get(dateCol);
                double v = d.number(row, measure);
                if (date == null || date.length() < 7 || Double.isNaN(v)) {
                    continue;
                }
                byMonth.merge(date.substring(0, 7), v, Double::sum);
            }
            if (byMonth.size() < 2) {
                return List.of();
            }

            String firstKey = byMonth.firstKey();
            String lastKey = byMonth.lastKey();
            double first = byMonth.get(firstKey);
            double last = byMonth.get(lastKey);
            Map.Entry<String, Double> best = Collections.max(byMonth.entrySet(), Map.Entry.comparingByValue());

            String headline;
            if (first == 0) {
                headline = measure + " moved from " + fmt(first) + " in " + firstKey + " to " + fmt(last) + " in " + lastKey;
            } else {
                double change = (last - first) / Math.abs(first) * 100;
                headline = measure + (change >= 0 ? " grew " : " fell ") + pct(Math.abs(change))
                        + " from " + firstKey + " to " + lastKey;
            }
            String detail = "Best month: " + best.getKey() + " (" + fmt(best.getValue()) + "). Monthly totals: "
                    + byMonth.entrySet().stream()
                    .map(e -> e.getKey() + "=" + fmt(e.getValue()))
                    .collect(Collectors.joining(", "));
            return List.of(new Finding(name(), 3, headline, detail));
        }
    }

    /** Values outside the 1.5 x IQR fences. */
    static class OutlierStep implements Step {
        private final String col;

        OutlierStep(String col) {
            this.col = col;
        }

        public String name() {
            return "Outlier check on " + col;
        }

        public List<Finding> run(Dataset d) {
            double[] v = d.numbers(col);
            if (v.length < 4) {
                return List.of();
            }
            double[] s = v.clone();
            Arrays.sort(s);
            double q1 = quantile(s, 0.25);
            double q3 = quantile(s, 0.75);
            double iqr = q3 - q1;
            double low = q1 - 1.5 * iqr;
            double high = q3 + 1.5 * iqr;
            long count = Arrays.stream(v).filter(x -> x < low || x > high).count();

            if (count == 0) {
                return List.of(new Finding(name(), 1, "No outliers in " + col,
                        "All values fall within " + fmt(low) + " to " + fmt(high) + "."));
            }
            return List.of(new Finding(name(), 2,
                    count + " outlier(s) in " + col + " (" + pct(count * 100.0 / v.length) + " of values)",
                    "Expected range " + fmt(low) + " to " + fmt(high) + "; largest value is " + fmt(s[s.length - 1]) + "."));
        }
    }

    // ------------------------------------------------------------------
    // Planner: decides WHICH steps to run based on the data's shape
    // ------------------------------------------------------------------
    static List<Step> plan(Dataset d, int maxSteps) {
        List<String> numeric = new ArrayList<>();
        List<String> categorical = new ArrayList<>();
        String dateCol = null;

        for (String c : d.columns) {
            if (d.isDate(c)) {
                if (dateCol == null) {
                    dateCol = c;
                }
            } else if (d.isNumeric(c)) {
                numeric.add(c);
            } else {
                long distinct = d.values(c).stream().distinct().count();
                if (distinct >= 2 && distinct <= 20 && distinct < d.rows.size()) {
                    categorical.add(c);
                }
            }
        }

        String measure = pickMeasure(numeric);
        List<Step> steps = new ArrayList<>();
        steps.add(new ProfileStep());
        if (measure != null) {
            if (dateCol != null) {
                steps.add(new TrendStep(dateCol, measure));
            }
            for (String cat : categorical) {
                steps.add(new TopContributorsStep(cat, measure));
            }
            steps.add(new OutlierStep(measure));
        }
        for (String n : numeric) {
            steps.add(new SummaryStatsStep(n));
        }
        return steps.size() > maxSteps ? new ArrayList<>(steps.subList(0, maxSteps)) : steps;
    }

    /** Prefers a column that looks like money; otherwise the first numeric column. */
    static String pickMeasure(List<String> numeric) {
        for (String c : numeric) {
            String n = c.toLowerCase(Locale.ROOT);
            if (n.contains("amount") || n.contains("revenue") || n.contains("sales")
                    || n.contains("price") || n.contains("total") || n.contains("cost")) {
                return c;
            }
        }
        return numeric.isEmpty() ? null : numeric.get(0);
    }

    // ------------------------------------------------------------------
    // Executor
    // ------------------------------------------------------------------
    record Outcome(String stepName, boolean ok, String message) {}

    static class RunResult {
        final List<Finding> findings = new ArrayList<>();
        final List<Outcome> outcomes = new ArrayList<>();
    }

    static RunResult execute(List<Step> plan, Dataset data) {
        RunResult result = new RunResult();
        for (int i = 0; i < plan.size(); i++) {
            Step step = plan.get(i);
            System.out.printf("[%d/%d] %s%n", i + 1, plan.size(), step.name());
            try {
                List<Finding> found = step.run(data);
                result.findings.addAll(found);
                result.outcomes.add(new Outcome(step.name(), true, found.size() + " finding(s)"));
            } catch (Exception e) {
                result.outcomes.add(new Outcome(step.name(), false, "failed: " + e.getMessage()));
            }
        }
        return result;
    }

    // ------------------------------------------------------------------
    // Report writer
    // ------------------------------------------------------------------
    static List<Finding> keyTakeaways(List<Finding> findings, int limit) {
        return findings.stream()
                .sorted(Comparator.comparingInt(Finding::priority).reversed()) // stable sort keeps plan order
                .limit(limit)
                .toList();
    }

    static String buildReport(String source, RunResult result) {
        StringBuilder md = new StringBuilder();
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        md.append("# Insight Report\n\n");
        md.append("_Source: ").append(source).append(" | Generated: ").append(now).append("_\n\n");

        md.append("## Key takeaways\n\n");
        for (Finding f : keyTakeaways(result.findings, 3)) {
            md.append("- ").append(f.headline()).append("\n");
        }

        md.append("\n## Detailed findings\n\n");
        Map<String, List<Finding>> byStep = result.findings.stream()
                .collect(Collectors.groupingBy(Finding::step, LinkedHashMap::new, Collectors.toList()));
        byStep.forEach((step, list) -> {
            md.append("### ").append(step).append("\n\n");
            for (Finding f : list) {
                md.append("- **").append(f.headline()).append("**: ").append(f.detail()).append("\n");
            }
            md.append("\n");
        });

        md.append("## Analysis plan\n\n");
        int n = 1;
        for (Outcome o : result.outcomes) {
            md.append(n++).append(". ").append(o.stepName()).append(" - ").append(o.message()).append("\n");
        }
        return md.toString();
    }

    // ------------------------------------------------------------------
    // Demo data
    // ------------------------------------------------------------------
    static void writeDemoCsv(Path path) throws IOException {
        Random rnd = new Random(42);
        String[] regions = {"North", "South", "East", "West"};
        double[] weights = {0.50, 0.20, 0.20, 0.10};
        String[] products = {"Laptop", "Phone", "Tablet"};
        double[] prices = {900, 600, 400};

        List<String> lines = new ArrayList<>();
        lines.add("date,region,product,amount,units");
        for (int i = 0; i < 150; i++) {
            int month = rnd.nextInt(6) + 1;
            int day = rnd.nextInt(28) + 1;

            double roll = rnd.nextDouble();
            int r = 0;
            double acc = weights[0];
            while (roll > acc && r < regions.length - 1) {
                r++;
                acc += weights[r];
            }
            int p = rnd.nextInt(products.length);
            int units = rnd.nextInt(5) + 1;

            double growth = 1 + 0.08 * (month - 1);
            double amount = prices[p] * units * growth * (0.9 + 0.2 * rnd.nextDouble());
            if (i % 50 == 25) {
                amount *= 6; // planted outliers
            }
            String unitsCell = (i == 49) ? "" : String.valueOf(units); // planted missing value
            lines.add(String.format(Locale.US, "2026-%02d-%02d,%s,%s,%.2f,%s",
                    month, day, regions[r], products[p], amount, unitsCell));
        }
        Files.write(path, lines);
    }

    // ------------------------------------------------------------------
    // Main
    // ------------------------------------------------------------------
    public static void main(String[] args) throws IOException {
        String input = "sales.csv";
        String out = "insight_report.md";
        boolean demo = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--demo" -> demo = true;
                case "--out" -> {
                    if (i + 1 >= args.length) {
                        System.err.println("--out needs a file name");
                        System.exit(1);
                    }
                    out = args[++i];
                }
                default -> input = args[i];
            }
        }

        Path inputPath = Path.of(input);
        if (demo) {
            writeDemoCsv(inputPath);
            System.out.println("Demo data written to " + inputPath);
        }
        if (!Files.exists(inputPath)) {
            System.err.println("File not found: " + inputPath + " (use --demo to create sample data)");
            System.exit(1);
        }

        Dataset data = Dataset.fromCsv(inputPath);
        if (data.rows.isEmpty()) {
            System.err.println("No data rows found in " + inputPath);
            System.exit(1);
        }

        System.out.println("Planning analysis...");
        List<Step> plan = plan(data, 8);
        System.out.println("Plan has " + plan.size() + " steps\n");

        RunResult result = execute(plan, data);
        String report = buildReport(inputPath.toString(), result);
        Files.writeString(Path.of(out), report);

        System.out.println("\nKey takeaways:");
        for (Finding f : keyTakeaways(result.findings, 3)) {
            System.out.println(" - " + f.headline());
        }
        System.out.println("\nReport written to " + out);
    }
}
