package com.evolution.analysis.spring.registration;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.evidence.CapabilityGapRecord;
import com.evolution.analysis.spring.SpringFrameworkEvidence;
import com.evolution.analysis.spring.condition.*;
import java.util.*;

/** Passive Boot sorter over supplied effective metadata, after selection/exclusions/filters/replacements. */
public final class AutoConfigurationOrdering {
    private AutoConfigurationOrdering() {}
    public enum Availability { PRESENT, ABSENT, UNKNOWN }
    public enum Status { ORDERED, UNKNOWN, ERROR, LIMIT_EXCEEDED }
    public record Metadata(String className, boolean selected, Availability availability, int order,
                            List<String> before, List<String> after, ConditionEvidence evidence) {
        public Metadata {
            className = RegistrationIdentity.text(className); Objects.requireNonNull(availability); Objects.requireNonNull(evidence);
            before = ContractChecks.distinctInOrder(before.stream().map(RegistrationIdentity::text).toList(), "effective before metadata");
            after = ContractChecks.distinctInOrder(after.stream().map(RegistrationIdentity::text).toList(), "effective after metadata");
        }
    }
    public record Limits(int maxClasses, int maxSteps) {
        public Limits { if (maxClasses < 1 || maxSteps < 1) throw new IllegalArgumentException("Positive sorter bounds required"); }
        public static Limits conservative() { return new Limits(10000, 100000); }
    }
    public record Result(ContentDigest inputIdentity, Status status, List<String> orderedClasses,
                         List<String> selectedClasses, List<RegistrationProcessing.Issue> issues,
                         List<CapabilityGapRecord> capabilityGaps, int steps) {
        public Result {
            Objects.requireNonNull(inputIdentity); Objects.requireNonNull(status); orderedClasses = List.copyOf(orderedClasses);
            selectedClasses = List.copyOf(selectedClasses); issues = List.copyOf(issues); capabilityGaps = List.copyOf(capabilityGaps);
            if (status != Status.ORDERED && !orderedClasses.isEmpty()) throw new IllegalArgumentException("Incomplete sorter cannot export an order");
        }
        public ContentDigest identity() { return RegistrationIdentity.digest(this); }
    }
    public static Result order(SpringBuildContext build, SpringFrameworkEvidence framework, List<Metadata> metadata, Limits limits) {
        return new Sort(build, framework, metadata, limits).run();
    }
    private static final class Sort {
        final SpringBuildContext build;
        final SpringFrameworkEvidence framework;
        final Limits limits;
        final List<Metadata> metadata;
        final List<String> selected;
        final Map<String, Metadata> byName = new HashMap<>();
        final LinkedHashMap<String, Metadata> closure = new LinkedHashMap<>();
        final List<RegistrationProcessing.Issue> issues = new ArrayList<>();
        final ContentDigest input;
        int steps;
        Sort(SpringBuildContext build, SpringFrameworkEvidence framework, List<Metadata> metadata, Limits limits) {
            this.build = Objects.requireNonNull(build); this.framework = Objects.requireNonNull(framework); this.limits = Objects.requireNonNull(limits);
            this.metadata = ContractChecks.sortedDistinct(metadata, Comparator.comparing(Metadata::className), "auto-configuration metadata");
            this.metadata.forEach(m -> byName.put(m.className(), m));
            selected = this.metadata.stream().filter(Metadata::selected).map(Metadata::className).toList();
            input = RegistrationIdentity.digest(Map.of("schema", "spring-auto-configuration-order-v1", "build", build.identity(),
                    "framework", framework.identity(), "metadata", this.metadata, "limits", limits, "semantics", RegistrationProcessing.SEMANTICS));
        }
        Result run() {
            var evidence = metadata.isEmpty() ? new ConditionEvidence.Derived(List.of(input), RegistrationProcessing.PROVIDER, "empty-sort") : metadata.getFirst().evidence();
            if (!build.containsFrameworkEvidence(framework)) issue(RegistrationProcessing.Reason.BUILD_CONTEXT_MISMATCH, "auto-configuration-sort", evidence);
            // The exact S26 implementation inspected at R0 is Boot 3.4.0; other versions need their own conformance.
            if (!framework.acceptedConditionFragment(true) || framework.artifacts().stream().noneMatch(a -> a.coordinate().equals("org.springframework.boot:spring-boot-autoconfigure:3.4.0")))
                issue(RegistrationProcessing.Reason.VERSION_FRAGMENT_NOT_VALIDATED, "boot-sorter", evidence);
            if (metadata.size() > limits.maxClasses()) return limited(evidence);
            for (var m : metadata) if (m.evidence() instanceof ConditionEvidence.Source source) {
                if (!build.containsSource(source)) issue(RegistrationProcessing.Reason.SOURCE_EVIDENCE_MISMATCH, m.className(), m.evidence());
                if (source.span().isEmpty()) issue(RegistrationProcessing.Reason.MISSING_SOURCE_SPAN, m.className(), m.evidence());
            }
            if (!issues.isEmpty()) return result(Status.UNKNOWN, List.of());
            // Reproduce the metadata closure's before-then-after visitation; no recursive Java stack.
            Deque<Iterator<String>> work = new ArrayDeque<>(); work.push(selected.iterator());
            Set<String> examined = new HashSet<>();
            while (!work.isEmpty()) {
                if (!tick()) return limited(evidence);
                if (!work.peek().hasNext()) { work.pop(); continue; }
                String name = work.peek().next(); if (!examined.add(name)) continue;
                var m = byName.get(name);
                if (m == null || m.availability() == Availability.UNKNOWN || m.selected() && m.availability() == Availability.ABSENT) {
                    issue(RegistrationProcessing.Reason.AUTO_CONFIGURATION_METADATA_INCOMPLETE, name, m == null ? evidence : m.evidence()); continue;
                }
                if (m.availability() == Availability.ABSENT) continue;
                closure.put(name, m);
                List<String> references = new ArrayList<>(m.before()); references.addAll(m.after()); work.push(references.iterator());
            }
            if (!issues.isEmpty()) return result(Status.UNKNOWN, List.of());
            List<String> seed = new ArrayList<>(selected); seed.sort(Comparator.comparingInt((String n) -> byName.get(n).order()).thenComparing(Comparator.naturalOrder()));
            List<String> pending = new ArrayList<>(seed); pending.addAll(closure.keySet());
            Map<String, LinkedHashSet<String>> predecessors = new HashMap<>(); closure.keySet().forEach(n -> predecessors.put(n, new LinkedHashSet<>()));
            for (var m : closure.values()) {
                for (String name : m.after()) { if (!tick()) return limited(evidence); if (closure.containsKey(name)) predecessors.get(m.className()).add(name); }
                for (String name : m.before()) { if (!tick()) return limited(evidence); if (closure.containsKey(name)) predecessors.get(name).add(m.className()); }
            }
            LinkedHashSet<String> sorted = new LinkedHashSet<>(); Set<String> processing = new HashSet<>();
            record Frame(String name, Iterator<String> predecessors) {}
            Deque<Frame> stack = new ArrayDeque<>();
            while (!pending.isEmpty()) {
                if (!tick()) return limited(evidence);
                String start = pending.removeFirst();
                processing.add(start);
                stack.push(new Frame(start, orderedPredecessors(start, pending, predecessors).iterator()));
                while (!stack.isEmpty()) {
                    if (!tick() || steps > limits.maxSteps()) return limited(evidence);
                    var frame = stack.peek();
                    if (!frame.predecessors().hasNext()) {
                        processing.remove(frame.name()); sorted.add(frame.name()); stack.pop(); continue;
                    }
                    String after = frame.predecessors().next();
                    if (processing.contains(after)) {
                        issue(RegistrationProcessing.Reason.ORDER_CYCLE, frame.name() + " -> " + after, byName.get(frame.name()).evidence());
                        return result(Status.ERROR, List.of());
                    }
                    if (!sorted.contains(after) && pending.contains(after)) {
                        processing.add(after); stack.push(new Frame(after, orderedPredecessors(after, pending, predecessors).iterator()));
                    }
                }
            }
            return result(Status.ORDERED, sorted.stream().filter(selected::contains).toList());
        }
        List<String> orderedPredecessors(String name, List<String> pending, Map<String, LinkedHashSet<String>> predecessors) {
            // Keep Boot's pending-index priority, but retain every constraint when several
            // predecessors have left pending. A tie must not hide a cycle or drop evidence.
            Map<String, Integer> positions = new HashMap<>();
            for (int i = 0; i < pending.size(); i++) { if (!tick()) break; positions.putIfAbsent(pending.get(i), i); }
            TreeSet<String> result = new TreeSet<>(Comparator.comparingInt((String n) -> positions.getOrDefault(n, -1))
                    .thenComparing(Comparator.naturalOrder()));
            for (String predecessor : predecessors.get(name)) { if (!tick()) break; result.add(predecessor); }
            return List.copyOf(result);
        }
        boolean tick() { if (steps >= limits.maxSteps()) return false; steps++; return true; }
        void issue(RegistrationProcessing.Reason reason, String subject, ConditionEvidence evidence) {
            issues.add(new RegistrationProcessing.Issue(reason, subject, Optional.empty(), List.of(evidence)));
        }
        Result limited(ConditionEvidence evidence) { issue(RegistrationProcessing.Reason.DISCOVERY_LIMIT, "auto-configuration-sort", evidence); return result(Status.LIMIT_EXCEEDED, List.of()); }
        Result result(Status status, List<String> order) {
            var orderedIssues = issues.stream().distinct().sorted(Comparator.comparing(RegistrationProcessing.Issue::identity)).toList();
            return new Result(input, status, order, selected, orderedIssues, RegistrationProcessing.gaps(input, build, orderedIssues), steps);
        }
    }
}
