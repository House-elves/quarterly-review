///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS info.picocli:picocli:4.7.6
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The quarterly review: what you got done in a quarter, written the way your "Quarterly Accomplishments" doc is
 * (PTO, Presentations, then one section per project with short "Area: what" bullets). It reads your merged PRs and
 * closed issues for the quarter, and the weekly statuses you sent in it, and has Claude write the review. With
 * --save the review is saved for Elf Desk's Quarterly tab; it never sends or posts anything.
 */
@Command(name = "quarterly-review", mixinStandardHelpOptions = true, version = "1.0.0",
        description = "Writes your quarterly review from your GitHub work, with Claude")
public class QuarterlyReview implements Callable<Integer> {

    static final Path CONFIG_DIR = Path.of(System.getProperty("user.home"), ".config", "quarterly-review");
    static final Path RUNS = CONFIG_DIR.resolve("runs");
    static final Path WEEKLY_RUNS = Path.of(System.getProperty("user.home"), ".config", "weekly-status", "runs");
    static final Pattern QUARTER = Pattern.compile("(\\d{4})-Q([1-4])");
    static final ObjectMapper JSON = new ObjectMapper();
    static final int LIMIT = 400;

    @Option(names = "--quarter", paramLabel = "YYYY-QN", description = "The quarter (default: the last one that ended)")
    String quarter;

    @Option(names = "--save", description = "Save the review under ~/.config/quarterly-review/runs for Elf Desk")
    boolean save;

    String githubUser;
    String displayName;
    Set<String> excludeOrgs;

    @Override
    public Integer call() throws Exception {
        loadConfig();
        if (quarter == null) quarter = lastQuarter(LocalDate.now());
        Matcher m = QUARTER.matcher(quarter);
        if (!m.matches()) {
            System.err.println("--quarter is like 2026-Q3");
            return 2;
        }
        int year = Integer.parseInt(m.group(1)), q = Integer.parseInt(m.group(2));
        LocalDate start = LocalDate.of(year, (q - 1) * 3 + 1, 1);
        LocalDate end = start.plusMonths(3).minusDays(1);
        LocalDate until = end.isAfter(LocalDate.now()) ? LocalDate.now() : end; // the quarter so far
        String title = "Q" + q + " " + year;

        System.out.println("Reading GitHub for " + title + " (" + start + " to " + until + ")...");
        String range = start + ".." + until;
        List<JsonNode> prs = mine(search("prs", "--merged-at", range));
        List<JsonNode> issues = mine(search("issues", "--closed", range));
        System.out.println("Found " + prs.size() + " merged PRs and " + issues.size() + " closed issues");
        List<String> weekly = weeklyStatuses(start, until);
        System.out.println("Found " + weekly.size() + " weekly statuses sent in the quarter");

        System.out.println("Writing the review with Claude...");
        String body = write(title, start, until, prs, issues, weekly, previousReview(quarter));
        if (body == null) return 1;

        if (!save) {
            System.out.println("\n" + body);
            return 0;
        }
        String id = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        ObjectNode run = JSON.createObjectNode().put("id", id).put("generatedAt", Instant.now().toString())
                .put("quarter", quarter).put("title", title).put("start", start.toString()).put("end", end.toString())
                .put("until", until.toString()).put("prs", prs.size()).put("issues", issues.size())
                .put("weeklyStatuses", weekly.size()).put("body", body);
        Files.createDirectories(RUNS);
        Files.writeString(RUNS.resolve(id + ".json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(run));
        System.out.println("Saved review " + id);
        return 0;
    }

    /** The last quarter that ended before {@code today}: in October 2026, 2026-Q3. */
    static String lastQuarter(LocalDate today) {
        int q = (today.getMonthValue() - 1) / 3; // this quarter, 0-based
        return q == 0 ? (today.getYear() - 1) + "-Q4" : today.getYear() + "-Q" + q;
    }

    void loadConfig() throws IOException {
        Map<String, String> raw = new HashMap<>();
        Path config = CONFIG_DIR.resolve("config");
        if (Files.exists(config)) {
            for (String line : Files.readAllLines(config)) {
                line = line.strip();
                int eq = line.indexOf('=');
                if (!line.startsWith("#") && eq > 0) raw.put(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
            }
        }
        githubUser = raw.getOrDefault("GITHUB_USER", "");
        displayName = raw.getOrDefault("DISPLAY_NAME", githubUser);
        excludeOrgs = Stream.of(raw.getOrDefault("EXCLUDE_ORGS", "").split(",")).map(String::strip)
                .filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        if (githubUser.isBlank()) throw new IllegalStateException("GITHUB_USER isn't set in " + config);
    }

    /** Outside the excluded orgs; what the review needs of each, with the start of its description. */
    List<JsonNode> mine(List<JsonNode> found) {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode n : found) {
            String repo = n.path("repository").path("nameWithOwner").asText("");
            if (excludeOrgs.contains(repo.split("/")[0])) continue;
            String body = n.path("body").asText("").replaceAll("\\s+", " ").strip();
            out.add(JSON.createObjectNode().put("repo", repo).put("title", n.path("title").asText())
                    .put("url", n.path("url").asText()).put("closed", n.path("closedAt").asText(""))
                    .put("about", body.length() > 300 ? body.substring(0, 300) + "…" : body));
        }
        return out;
    }

    List<JsonNode> search(String type, String filter, String range) {
        List<String> cmd = List.of("gh", "search", type, "--author", githubUser, filter, range, "--limit", String.valueOf(LIMIT),
                "--json", "repository,title,url,closedAt,body");
        try {
            Process p = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.INHERIT).start();
            p.getOutputStream().close();
            // Read while it runs: gh waits for its output to be read before it exits.
            CompletableFuture<String> out = CompletableFuture.supplyAsync(() -> {
                try {
                    return new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    return "";
                }
            });
            if (!p.waitFor(120, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("gh search " + type + " took too long");
            }
            String stdout = out.get(10, TimeUnit.SECONDS);
            if (p.exitValue() != 0) throw new IOException("gh search " + type + " failed (exit " + p.exitValue() + ")");
            List<JsonNode> results = new ArrayList<>();
            JSON.readTree(stdout).forEach(results::add);
            return results;
        } catch (Exception e) {
            throw new IllegalStateException("Couldn't read GitHub: " + e.getMessage(), e);
        }
    }

    /** What you sent as weekly status in the quarter (your edits included): talks and other non-GitHub work show up there. */
    static List<String> weeklyStatuses(LocalDate start, LocalDate until) {
        if (!Files.isDirectory(WEEKLY_RUNS)) return List.of();
        List<String> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(WEEKLY_RUNS)) {
            for (Path f : files.filter(f -> f.toString().endsWith(".json")).sorted().toList()) {
                String day = f.getFileName().toString().substring(0, 10);
                if (day.compareTo(start.toString()) < 0 || day.compareTo(until.plusDays(7).toString()) > 0) continue;
                JsonNode d = JSON.readTree(f.toFile());
                String sent = d.path("sentBody").asText("");
                if (!sent.isBlank()) out.add(sent);
            }
        } catch (IOException e) {
            System.err.println("Couldn't read the weekly statuses: " + e.getMessage());
        }
        return out;
    }

    /** The review saved for the quarter before, as an example of the style; null when there is none. */
    static String previousReview(String quarter) {
        Matcher m = QUARTER.matcher(quarter);
        if (!m.matches() || !Files.isDirectory(RUNS)) return null;
        int year = Integer.parseInt(m.group(1)), q = Integer.parseInt(m.group(2));
        String before = q == 1 ? (year - 1) + "-Q4" : year + "-Q" + (q - 1);
        try (Stream<Path> files = Files.list(RUNS)) {
            for (Path f : files.filter(f -> f.toString().endsWith(".json")).sorted(java.util.Comparator.reverseOrder()).toList()) {
                JsonNode d = JSON.readTree(f.toFile());
                if (before.equals(d.path("quarter").asText())) return d.path("body").asText(null);
            }
        } catch (IOException e) {
            return null;
        }
        return null;
    }

    /** How the doc reads: one of its quarters, as the example when there is no earlier saved review. */
    static final String EXAMPLE = """
            # Q4 2025

            ## PTO
            - Big part of Q4 is PTO (Summer holidays)

            ## Presentations
            - IBM TechX Sydney: Quarkus AI
            - API Days Melbourne: Quarkus GraphQL
            - Quarkus Insights #222: AI Assisted Development with Quarkus Dev MCP

            ## Quarkus
            - Added support for i18n in Dev UI
            - Dev UI Agroal: Added support for json cols
            - Dev Assistant: Allow response definition when creating an AI feature
            - Extension translator (in progress)
            - Dev MCP: Reduce the payload for some methods

            ## Chappie
            - Added Search Docs MCP that use the RAG to reach docs (With Ozan)
            - Added support for Chat History
            """;

    String write(String title, LocalDate start, LocalDate until, List<JsonNode> prs, List<JsonNode> issues,
            List<String> weekly, String previous) {
        ObjectNode data = JSON.createObjectNode();
        data.set("merged_pull_requests", JSON.valueToTree(prs));
        data.set("closed_issues", JSON.valueToTree(issues));
        ArrayNode w = data.putArray("weekly_statuses_sent");
        weekly.forEach(w::add);
        String prompt = """
                You are writing %s's quarterly accomplishments for %s (%s to %s). %s is a software engineer at
                IBM working on Quarkus and related open source projects. The review goes into a running document,
                newest quarter first, that their manager reads.

                Write it in exactly this shape (Markdown):

                # %s

                ## PTO
                - (only if the data shows time off; otherwise leave this section out)

                ## Presentations
                - (talks, conference sessions, Quarkus Insights episodes, meetups hosted, as "Event: Topic")

                ## [Project]
                - [Area]: [what was done]

                Rules:
                - Group by project, the way the example does: "Quarkus" (quarkusio/quarkus and Quarkus core work),
                  then other projects by their own name (for example "Quarkus Agent MCP", "Quarkus JSON-RPC",
                  "SmallRye GraphQL", "MicroProfile GraphQL", "Quarkiverse", "Vert.x"). PRs to someone's fork of a
                  project belong to that project. Put the projects with the most significant work first.
                - One short bullet per feature or theme, prefixed with the area when there is one ("Dev UI: ...",
                  "Dev MCP: ...", "OpenAPI: ..."). Merge related PRs and issues into one bullet; never one bullet per PR.
                - Fold version bumps, dependency updates, releases and small fixes into one closing bullet per project,
                  like "Maintenance and small fixes" or "Releases 1.2 to 1.5".
                - Plain, past-tense, matter-of-fact. No links, no PR numbers, no counts of PRs, no adjectives like
                  "significant" or "major".
                - Only Presentations and PTO the data shows (the weekly statuses may mention them). If it shows none,
                  write the Presentations section with the single bullet "(add talks here)" so it can be filled in.
                - Write nothing before the "# %s" line and nothing after the last bullet.

                Example of the style (an earlier quarter):

                %s

                The data (merged pull requests and closed issues with the start of their description, and the weekly
                statuses sent in the quarter):

                %s
                """.formatted(displayName, title, start, until, displayName, title, title,
                previous != null ? previous : EXAMPLE, data.toPrettyString());
        try {
            Process p = new ProcessBuilder("claude", "-p", "--model", "sonnet").start();
            CompletableFuture<String> out = CompletableFuture.supplyAsync(() -> read(p.getInputStream()));
            CompletableFuture<String> err = CompletableFuture.supplyAsync(() -> read(p.getErrorStream()));
            try (var os = p.getOutputStream()) {
                os.write(prompt.getBytes(StandardCharsets.UTF_8));
            }
            if (!p.waitFor(6, TimeUnit.MINUTES)) {
                p.destroyForcibly();
                System.err.println("Claude took too long");
                return null;
            }
            String stdout = out.get(10, TimeUnit.SECONDS).strip();
            if (p.exitValue() != 0) {
                System.err.println("Claude failed: " + err.get(10, TimeUnit.SECONDS).strip());
                return null;
            }
            // The review starts at its "# Q3 2026" line: anything before it (a greeting a global CLAUDE.md asks for)
            // isn't the review.
            int at = stdout.indexOf("# " + title);
            if (at < 0) {
                System.err.println("Claude didn't write the review: " + stdout.substring(0, Math.min(300, stdout.length())));
                return null;
            }
            stdout = stdout.substring(at);
            if (stdout.endsWith("```")) stdout = stdout.substring(0, stdout.length() - 3);
            return stdout.strip();
        } catch (Exception e) {
            System.err.println("Claude error: " + e.getMessage());
            return null;
        }
    }

    static String read(java.io.InputStream in) {
        try {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    public static void main(String[] args) {
        System.exit(new CommandLine(new QuarterlyReview()).execute(args));
    }
}
