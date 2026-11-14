package ge.opensoft.openforce;

import static ge.opensoft.openforce.Core.*;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.*;

@RestController
public class ApiController {
    private final Core core;
    private static final String BASE = "/services/data/v68.0";
    private static final String ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private record Definition(String prefix, List<String> required, List<String> allowed) {}

    private static final Map<String, Definition> OBJECTS = new LinkedHashMap<>();

    static {
        OBJECTS.put(
                "Account",
                new Definition("001", List.of("Name"), List.of("Name", "Description", "Phone")));
        OBJECTS.put(
                "Contact",
                new Definition(
                        "003",
                        List.of("LastName"),
                        List.of("FirstName", "LastName", "Email", "AccountId")));
        OBJECTS.put(
                "Lead",
                new Definition(
                        "00Q",
                        List.of("LastName", "Company"),
                        List.of("FirstName", "LastName", "Company", "Email", "Status")));
        OBJECTS.put(
                "Opportunity",
                new Definition(
                        "006",
                        List.of("Name", "StageName", "CloseDate"),
                        List.of("Name", "StageName", "CloseDate", "AccountId", "Amount")));
    }

    public ApiController(Core core) {
        this.core = core;
    }

    @RequestMapping("/**")
    public ResponseEntity<?> dispatch(
            HttpServletRequest request, @RequestBody(required = false) byte[] bytes)
            throws Exception {
        return core.execute(
                request,
                bytes,
                this::route,
                e ->
                        List.of(
                                map(
                                        "message",
                                        e.getMessage(),
                                        "errorCode",
                                        switch (e.status) {
                                            case 400 -> "INVALID_FIELD";
                                            case 401 -> "INVALID_SESSION_ID";
                                            case 403 -> "INSUFFICIENT_ACCESS";
                                            case 404 -> "NOT_FOUND";
                                            default -> "REQUEST_ERROR";
                                        },
                                        "fields",
                                        List.of())));
    }

    private Result route() throws Exception {
        String path = core.request().getRequestURI().replaceAll("/+$", "");
        String method = core.request().getMethod();
        if (path.equals(BASE + "/query")) {
            requireMethod(method, "GET");
            return query();
        }
        core.parameters();
        if (path.equals(BASE + "/sobjects")) {
            requireMethod(method, "GET");
            return ok(
                    map(
                            "encoding",
                            "UTF-8",
                            "maxBatchSize",
                            200,
                            "sobjects",
                            OBJECTS.entrySet().stream()
                                    .map(e ->
                                            map(
                                                    "name",
                                                    e.getKey(),
                                                    "keyPrefix",
                                                    e.getValue().prefix))
                                    .toList()));
        }
        Matcher route = Pattern.compile(BASE + "/sobjects/([^/]+)(?:/([^/]+))?").matcher(path);
        if (!route.matches()) throw new Failure(404, "Resource not found");
        String name = route.group(1);
        Definition definition = metadata(name);
        String identifier = route.group(2);
        if ("describe".equals(identifier)) {
            requireMethod(method, "GET");
            return ok(
                    map(
                            "name",
                            name,
                            "keyPrefix",
                            definition.prefix,
                            "createable",
                            Set.of("admin", "editor").contains(core.actor().get("role")),
                            "fields",
                            known(definition).stream()
                                    .map(field ->
                                            map(
                                                    "name",
                                                    field,
                                                    "nillable",
                                                    !definition.required.contains(field),
                                                    "createable",
                                                    definition.allowed.contains(field),
                                                    "updateable",
                                                    definition.allowed.contains(field)))
                                    .toList()));
        }
        if (identifier == null) {
            requireMethod(method, "POST");
            core.writable();
            var data = validate(name, core.body(), true);
            StringBuilder generated = new StringBuilder(definition.prefix);
            for (int i = 0; i < 12; i++)
                generated.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
            String id = casesafe(generated.toString());
            data.putAll(
                    map(
                            "Id",
                            id,
                            "OwnerId",
                            core.actor().get("user"),
                            "CreatedDate",
                            timestamp(),
                            "LastModifiedDate",
                            timestamp(),
                            "attributes",
                            map("type", name, "url", BASE + "/sobjects/" + name + "/" + id)));
            core.insert(name, id, data);
            return created(map("id", id, "success", true, "errors", List.of()));
        }
        var item = record(name, identifier);
        if (method.equals("GET")) return ok(item);
        requireMethod(method, "PATCH", "DELETE");
        core.writable();
        if (method.equals("DELETE")) {
            if (name.equals("Account")) {
                for (String child : List.of("Contact", "Opportunity")) {
                    if (core.rows(child).stream()
                            .anyMatch(row -> Objects.equals(row.get("AccountId"), item.get("Id"))))
                        throw new Failure(400, "Account still has related records");
                }
            }
            core.delete(name, item.get("Id"));
        } else {
            item.putAll(validate(name, core.body(), false));
            item.put("LastModifiedDate", timestamp());
            core.save(name, item.get("Id"), item);
        }
        return empty();
    }

    static String casesafe(String identifier) {
        StringBuilder suffix = new StringBuilder();
        for (int start = 0; start < 15; start += 5) {
            int flags = 0;
            for (int j = 0; j < 5; j++)
                if (Character.isUpperCase(identifier.charAt(start + j))) flags |= 1 << j;
            suffix.append("ABCDEFGHIJKLMNOPQRSTUVWXYZ012345".charAt(flags));
        }
        return identifier + suffix;
    }

    private Definition metadata(String name) {
        Definition value = OBJECTS.get(name);
        if (value == null) throw new Failure(404, "Unknown sObject");
        return value;
    }

    private List<String> known(Definition definition) {
        var fields = new ArrayList<>(List.of("Id", "OwnerId", "CreatedDate", "LastModifiedDate"));
        fields.addAll(definition.allowed);
        return fields;
    }

    private boolean visible(Map<String, Object> item) {
        return core.actor().get("role").equals("admin")
                || Objects.equals(item.get("OwnerId"), core.actor().get("user"));
    }

    private Map<String, Object> record(String name, String identifier) throws Exception {
        metadata(name);
        if (!identifier.matches("[A-Za-z0-9]{15}(?:[A-Za-z0-9]{3})?"))
            throw new Failure(400, "Invalid record ID");
        String canonical = casesafe(identifier.substring(0, 15));
        if (identifier.length() == 18 && !identifier.equals(canonical))
            throw new Failure(400, "Invalid ID checksum or case");
        var item = core.get(name, canonical);
        if (!visible(item)) throw new Failure(404, "Record not found");
        return item;
    }

    private Map<String, Object> validate(String name, Object body, boolean creating)
            throws Exception {
        Definition definition = metadata(name);
        var value =
                object(
                        body,
                        new HashSet<>(definition.allowed),
                        creating ? new HashSet<>(definition.required) : Set.of());
        for (var entry : value.entrySet()) {
            String key = entry.getKey();
            Object field = entry.getValue();
            if (key.equals("Amount")) {
                if (!(field instanceof Number number)
                        || !Double.isFinite(number.doubleValue())
                        || number.doubleValue() <= -1e15
                        || number.doubleValue() >= 1e15)
                    throw new Failure(400, "Amount must be a finite number");
            } else {
                text(
                        field,
                        key.equals("Description") ? 65536 : 255,
                        !definition.required.contains(key));
            }
            if (key.equals("AccountId"))
                entry.setValue(record("Account", (String) field).get("Id"));
            if (key.equals("CloseDate")) {
                try {
                    if (!LocalDate.parse((String) field).toString().equals(field))
                        throw new DateTimeParseException("Use YYYY-MM-DD", (String) field, 0);
                } catch (DateTimeParseException error) {
                    throw new Failure(400, "CloseDate must be an ISO date");
                }
            }
            if (key.equals("StageName")
                    && !List.of(
                                    "Prospecting",
                                    "Qualification",
                                    "Proposal/Price Quote",
                                    "Negotiation/Review",
                                    "Closed Won",
                                    "Closed Lost")
                            .contains(field)) throw new Failure(400, "Unsupported stage");
            if (key.equals("Status")
                    && !List.of(
                                    "Open - Not Contacted",
                                    "Working - Contacted",
                                    "Closed - Converted",
                                    "Closed - Not Converted")
                            .contains(field)) throw new Failure(400, "Unsupported lead status");
        }
        return value;
    }

    private Result query() throws Exception {
        core.parameters("q");
        Matcher query =
                Pattern.compile(
                                "SELECT ([A-Za-z, ]+) FROM (\\w+)(?: WHERE (\\w+) ="
                                    + " '((?:[^'\\\\]|\\\\['\\\\])*)')?(?: ORDER BY (\\w+)(?:"
                                    + " (ASC|DESC))?(?: NULLS (FIRST|LAST))?)?(?: LIMIT ([0-9]+))?",
                                Pattern.CASE_INSENSITIVE)
                        .matcher(core.argument("q", ""));
        if (!query.matches()) throw new Failure(400, "Unsupported SOQL query");
        String name = query.group(2), field = query.group(3), order = query.group(5);
        var fields = known(metadata(name));
        var selected = Arrays.stream(query.group(1).split(",", -1)).map(String::strip).toList();
        if (!fields.containsAll(selected) || (field != null && !fields.contains(field)))
            throw new Failure(400, "Unknown field");
        String value =
                query.group(4) == null ? null : query.group(4).replaceAll("\\\\(['\\\\])", "$1");
        var data =
                new ArrayList<>(
                        core.rows(name).stream()
                                .filter(item ->
                                        visible(item)
                                                && (field == null
                                                        || String.valueOf(
                                                                        item.getOrDefault(
                                                                                field, ""))
                                                                .equals(value)))
                                .toList());
        if (order != null) {
            if (!fields.contains(order)
                    || Set.of("Description", "StageName", "Status", "AccountId").contains(order))
                throw new Failure(400, "Unsupported sort field");
            var present =
                    new ArrayList<>(data.stream().filter(item -> item.get(order) != null).toList());
            var absent = data.stream().filter(item -> item.get(order) == null).toList();
            Comparator<Map<String, Object>> comparator =
                    order.equals("Amount")
                            ? Comparator.comparingDouble(
                                    item -> ((Number) item.get(order)).doubleValue())
                            : Comparator.comparing(
                                    item -> ((String) item.get(order)).toUpperCase(Locale.ROOT));
            if ("DESC".equalsIgnoreCase(query.group(6))) comparator = comparator.reversed();
            present.sort(comparator);
            data.clear();
            if (!"LAST".equalsIgnoreCase(query.group(7))) data.addAll(absent);
            data.addAll(present);
            if ("LAST".equalsIgnoreCase(query.group(7))) data.addAll(absent);
        }
        if (query.group(8) != null)
            data =
                    new ArrayList<>(
                            data.subList(
                                    0, Math.min(data.size(), integer(query.group(8), 0, 2000))));
        if (data.size() > 2000)
            throw new Failure(400, "Use LIMIT up to 2000; query locators are not implemented");
        List<Map<String, Object>> results = new ArrayList<>();
        for (var item : data) {
            var result = new LinkedHashMap<String, Object>();
            for (String key : selected) result.put(key, item.get(key));
            result.put("attributes", item.get("attributes"));
            results.add(result);
        }
        return ok(map("totalSize", results.size(), "done", true, "records", results));
    }

    private static void requireMethod(String actual, String... methods) {
        if (!List.of(methods).contains(actual)) throw new Failure(405, "Method not allowed");
    }
}
