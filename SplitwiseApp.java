import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.awt.Desktop;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splitwise India - Fullstack Java Application
 *
 * Provides:
 *  1. Embedded Java HTTP Server serving the modern HTML5/Tailwind GUI
 *  2. REST API endpoints for Groups, Expenses, Settlements, and Net Balances
 *  3. Greedy Debt-Simplification Algorithm
 *  4. Interactive Console CLI (Terminal Mode)
 *  5. Optional Native Desktop Swing GUI launcher (--desktop)
 *
 * Compile:  javac SplitwiseApp.java
 * Run:      java SplitwiseApp
 */

public class SplitwiseApp {

    // ---------------------------------------------------------------- Models

    public static class Expense {
        public final String id;
        public final String description;
        public final String payer;
        public final long total;                       // in paise/cents
        public final Map<String, Long> shares;         // member -> amount owed (paise)
        public final boolean settlement;
        public final String category;
        public final String date;
        public final String notes;

        public Expense(String description, String payer, Map<String, Long> shares, boolean settlement) {
            this("exp_" + System.currentTimeMillis() + "_" + (int)(Math.random() * 1000),
                 description, payer, shares, settlement, "restaurant", "Oct 24, 2024", "");
        }

        public Expense(String id, String description, String payer, Map<String, Long> shares,
                       boolean settlement, String category, String date, String notes) {
            this.id = id != null ? id : "exp_" + System.currentTimeMillis();
            this.description = description;
            this.payer = payer;
            this.shares = new LinkedHashMap<>(shares);
            this.settlement = settlement;
            this.category = category != null && !category.isEmpty() ? category : "restaurant";
            this.date = date != null && !date.isEmpty() ? date : "Oct 24, 2024";
            this.notes = notes != null ? notes : "";
            long sum = 0;
            for (long s : shares.values()) sum += s;
            this.total = sum;
        }
    }

    public static class Group {
        public String name;
        public final List<String> members = new ArrayList<>();
        public final List<Expense> expenses = new ArrayList<>();

        public Group(String name) {
            this.name = name;
        }

        /** Net balance per member: positive = is owed money, negative = owes money. */
        public synchronized Map<String, Long> netBalances() {
            Map<String, Long> net = new LinkedHashMap<>();
            for (String m : members) net.put(m, 0L);
            for (Expense e : expenses) {
                net.merge(e.payer, e.total, Long::sum);
                for (Map.Entry<String, Long> s : e.shares.entrySet()) {
                    net.merge(s.getKey(), -s.getValue(), Long::sum);
                }
            }
            return net;
        }

        /** Greedy debt simplification: fewest payments needed to settle the group. */
        public synchronized List<String> simplifiedDebts() {
            Map<String, Long> net = netBalances();
            List<String> result = new ArrayList<>();
            while (true) {
                String maxCreditor = null, maxDebtor = null;
                for (Map.Entry<String, Long> e : net.entrySet()) {
                    if (maxCreditor == null || e.getValue() > net.get(maxCreditor)) maxCreditor = e.getKey();
                    if (maxDebtor == null || e.getValue() < net.get(maxDebtor)) maxDebtor = e.getKey();
                }
                if (maxCreditor == null || net.get(maxCreditor) <= 0 || net.get(maxDebtor) >= 0) break;
                long amount = Math.min(net.get(maxCreditor), -net.get(maxDebtor));
                if (amount <= 0) break;
                result.add(maxDebtor + " owes " + maxCreditor + "  " + money(amount));
                net.merge(maxCreditor, -amount, Long::sum);
                net.merge(maxDebtor, amount, Long::sum);
            }
            return result;
        }
    }

    // ----------------------------------------------------------------- State

    static final Scanner scanner = new Scanner(System.in);
    public static final Map<String, Group> groups = new LinkedHashMap<>();
    private static int serverPort = 8080;

    // ----------------------------------------------------------- Initial Seed

    public static void initSeedData() {
        if (!groups.isEmpty()) return;

        // Group 1: Goa Trip
        Group goa = new Group("Goa Trip");
        goa.members.addAll(Arrays.asList("Rahul", "Priya", "Amit", "You"));

        // Expenses for Goa Trip
        Map<String, Long> cabShares = new LinkedHashMap<>();
        cabShares.put("Rahul", 35000L);
        cabShares.put("Priya", 35000L);
        cabShares.put("Amit", 35000L);
        cabShares.put("You", 35000L);
        goa.expenses.add(new Expense("exp_cab_1", "Airport Cab - Mopa to Candolim", "Rahul", cabShares, false, "transport", "Oct 21, 2024", "Prepaid taxi receipt #TX-882"));

        Map<String, Long> blinkitShares = new LinkedHashMap<>();
        blinkitShares.put("Rahul", 46000L);
        blinkitShares.put("Priya", 46000L);
        blinkitShares.put("Amit", 46000L);
        blinkitShares.put("You", 46000L);
        goa.expenses.add(new Expense("exp_groceries_1", "Blinkit - Snacks and Water", "You", blinkitShares, false, "groceries", "Oct 22, 2024", "Order #BL-99231"));

        Map<String, Long> chaiShares = new LinkedHashMap<>();
        chaiShares.put("Rahul", 9000L);
        chaiShares.put("Priya", 9000L);
        chaiShares.put("Amit", 9000L);
        chaiShares.put("You", 9000L);
        goa.expenses.add(new Expense("exp_chai_1", "Beach Shack Breakfast and Tea", "Amit", chaiShares, false, "food", "Oct 22, 2024", "Bill paid by cash"));

        Map<String, Long> scubaShares = new LinkedHashMap<>();
        scubaShares.put("Rahul", 175000L);
        scubaShares.put("Priya", 175000L);
        scubaShares.put("Amit", 175000L);
        scubaShares.put("You", 175000L);
        goa.expenses.add(new Expense("exp_scuba_1", "Scuba Diving at Grande Island", "Rahul", scubaShares, false, "entertainment", "Oct 23, 2024", "Includes gear and boat transfer"));

        Map<String, Long> dinnerShares = new LinkedHashMap<>();
        dinnerShares.put("Rahul", 132000L);
        dinnerShares.put("Priya", 132000L);
        dinnerShares.put("Amit", 132000L);
        dinnerShares.put("You", 132000L);
        goa.expenses.add(new Expense("exp_dinner_1", "Fishermans Wharf Dinner", "Priya", dinnerShares, false, "food", "Oct 23, 2024", "Table of 4"));

        Map<String, Long> fuelShares = new LinkedHashMap<>();
        fuelShares.put("Rahul", 30000L);
        fuelShares.put("Priya", 30000L);
        fuelShares.put("Amit", 30000L);
        fuelShares.put("You", 30000L);
        goa.expenses.add(new Expense("exp_fuel_1", "Scooter Rental and Petrol", "You", fuelShares, false, "transport", "Oct 24, 2024", "2 Honda Activas for 3 days"));

        groups.put(goa.name, goa);

        // Group 2: Koramangala Flat
        Group flat = new Group("Koramangala Flat");
        flat.members.addAll(Arrays.asList("Rohan", "Sneha", "Karan", "You"));

        Map<String, Long> rentShares = new LinkedHashMap<>();
        rentShares.put("Rohan", 1250000L);
        rentShares.put("Sneha", 1250000L);
        rentShares.put("Karan", 1250000L);
        rentShares.put("You", 1250000L);
        flat.expenses.add(new Expense("exp_rent_1", "October Flat Rent and Maintenance", "You", rentShares, false, "rent", "Oct 05, 2024", "Transferred via NEFT to landlord"));

        Map<String, Long> wifiShares = new LinkedHashMap<>();
        wifiShares.put("Rohan", 37500L);
        wifiShares.put("Sneha", 37500L);
        wifiShares.put("Karan", 37500L);
        wifiShares.put("You", 37500L);
        flat.expenses.add(new Expense("exp_wifi_1", "ACT Fibernet Broadband 300Mbps", "Sneha", wifiShares, false, "bills", "Oct 10, 2024", "Account #ACT-44910"));

        Map<String, Long> cookShares = new LinkedHashMap<>();
        cookShares.put("Rohan", 200000L);
        cookShares.put("Sneha", 200000L);
        cookShares.put("Karan", 200000L);
        cookShares.put("You", 200000L);
        flat.expenses.add(new Expense("exp_cook_1", "Monthly Cook Salary (Savitri Bai)", "Rohan", cookShares, false, "services", "Oct 15, 2024", "Paid via GPay"));

        Map<String, Long> zeptoShares = new LinkedHashMap<>();
        zeptoShares.put("Rohan", 32000L);
        zeptoShares.put("Sneha", 32000L);
        zeptoShares.put("Karan", 32000L);
        zeptoShares.put("You", 32000L);
        flat.expenses.add(new Expense("exp_zepto_1", "Zepto - Cleaning Supplies and Oil", "You", zeptoShares, false, "groceries", "Oct 18, 2024", "Detergent, dishwash, olive oil"));

        groups.put(flat.name, flat);

        // Group 3: Office Lunches
        Group office = new Group("Office Lunches");
        office.members.addAll(Arrays.asList("Vikram", "Ananya", "Nikhil", "You"));

        Map<String, Long> lunch1Shares = new LinkedHashMap<>();
        lunch1Shares.put("Vikram", 42000L);
        lunch1Shares.put("Ananya", 42000L);
        lunch1Shares.put("Nikhil", 42000L);
        lunch1Shares.put("You", 42000L);
        office.expenses.add(new Expense("exp_lunch_1", "Truffles Burgers and Pasta", "Vikram", lunch1Shares, false, "food", "Oct 22, 2024", "Friday team lunch"));

        Map<String, Long> chaiOfficeShares = new LinkedHashMap<>();
        chaiOfficeShares.put("Vikram", 12000L);
        chaiOfficeShares.put("Ananya", 12000L);
        chaiOfficeShares.put("Nikhil", 12000L);
        chaiOfficeShares.put("You", 12000L);
        office.expenses.add(new Expense("exp_chai_off", "Chai Point - Ginger Chai Flask", "Ananya", chaiOfficeShares, false, "food", "Oct 23, 2024", "Evening tea break"));

        groups.put(office.name, office);
    }

    // ------------------------------------------------------- Embedded Web Server

    public static int startHttpServer() {
        int port = 8080;
        while (port <= 8090) {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
                server.createContext("/", new StaticAndApiHandler());
                server.setExecutor(Executors.newCachedThreadPool());
                server.start();
                serverPort = port;
                return port;
            } catch (IOException e) {
                port++;
            }
        }
        return -1;
    }

    static class StaticAndApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();

            // Handle CORS preflight
            if (method.equalsIgnoreCase("OPTIONS")) {
                sendResponse(exchange, 204, "", "text/plain");
                return;
            }

            // 1. Static file serving (index.html)
            if (path.equals("/") || path.equals("/index.html")) {
                Path htmlPath = Paths.get("index.html");
                if (Files.exists(htmlPath)) {
                    byte[] htmlBytes = Files.readAllBytes(htmlPath);
                    exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
                    exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                    exchange.sendResponseHeaders(200, htmlBytes.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(htmlBytes);
                    }
                    return;
                }
            }

            // 2. REST API: GET /api/group?name=... or POST /api/group (create group)
            if (path.equals("/api/group") && method.equalsIgnoreCase("POST")) {
                String body = readRequestBody(exchange);
                String name = extractString(body, "name");
                if (name.isEmpty()) name = extractString(body, "group");
                if (!name.isEmpty()) {
                    synchronized (groups) {
                        Group g = groups.computeIfAbsent(name, Group::new);
                        if (!g.members.contains("You")) g.members.add("You");
                        sendResponse(exchange, 200, groupToJson(g), "application/json");
                    }
                    return;
                }
                sendResponse(exchange, 400, "{\"error\":\"Missing group name\"}", "application/json");
                return;
            }

            // 2a. REST API: POST /api/group/rename
            if ((path.equals("/api/group/rename") || path.equals("/api/rename-group")) && method.equalsIgnoreCase("POST")) {
                String body = readRequestBody(exchange);
                String oldName = extractString(body, "oldName");
                if (oldName.isEmpty()) oldName = extractString(body, "group");
                String newName = extractString(body, "newName");
                if (newName.isEmpty()) newName = extractString(body, "name");

                if (!oldName.isEmpty() && !newName.isEmpty()) {
                    synchronized (groups) {
                        Group g = groups.remove(oldName);
                        if (g != null) {
                            g.name = newName;
                            groups.put(newName, g);
                            sendResponse(exchange, 200, groupToJson(g), "application/json");
                            return;
                        }
                    }
                    sendResponse(exchange, 404, "{\"error\":\"Group not found\"}", "application/json");
                    return;
                }
                sendResponse(exchange, 400, "{\"error\":\"Missing oldName or newName\"}", "application/json");
                return;
            }

            // 2b. REST API: POST or DELETE /api/group/delete
            if ((path.equals("/api/group/delete") || path.equals("/api/delete-group") || (path.equals("/api/group") && method.equalsIgnoreCase("DELETE")))) {
                String body = readRequestBody(exchange);
                String name = extractString(body, "name");
                if (name.isEmpty()) name = extractString(body, "group");
                if (name.isEmpty()) {
                    String query = exchange.getRequestURI().getQuery();
                    if (query != null && query.contains("name=")) {
                        try {
                            String raw = query.substring(query.indexOf("name=") + 5);
                            int amp = raw.indexOf('&');
                            if (amp != -1) raw = raw.substring(0, amp);
                            name = java.net.URLDecoder.decode(raw, StandardCharsets.UTF_8.name());
                        } catch (Exception ignored) {}
                    }
                }

                if (!name.isEmpty()) {
                    synchronized (groups) {
                        groups.remove(name);
                        Group fallback = groups.isEmpty() ? null : groups.values().iterator().next();
                        if (fallback != null) {
                            sendResponse(exchange, 200, groupToJson(fallback), "application/json");
                        } else {
                            // If empty, create default group
                            Group defaultGroup = new Group("Personal Expenses");
                            defaultGroup.members.add("You");
                            groups.put(defaultGroup.name, defaultGroup);
                            sendResponse(exchange, 200, groupToJson(defaultGroup), "application/json");
                        }
                    }
                    return;
                }
                sendResponse(exchange, 400, "{\"error\":\"Missing group name to delete\"}", "application/json");
                return;
            }

            if (path.equals("/api/group") && method.equalsIgnoreCase("GET")) {
                String query = exchange.getRequestURI().getQuery();
                String groupName = "Goa Trip";
                if (query != null && query.contains("name=")) {
                    try {
                        String raw = query.substring(query.indexOf("name=") + 5);
                        int amp = raw.indexOf('&');
                        if (amp != -1) raw = raw.substring(0, amp);
                        groupName = java.net.URLDecoder.decode(raw, StandardCharsets.UTF_8.name());
                    } catch (Exception ignored) {}
                }

                Group g = groups.get(groupName);
                if (g == null && !groups.isEmpty()) {
                    g = groups.values().iterator().next();
                }
                if (g != null) {
                    sendResponse(exchange, 200, groupToJson(g), "application/json");
                } else {
                    sendResponse(exchange, 404, "{\"error\":\"Group not found\"}", "application/json");
                }
                return;
            }

            // 2b. REST API: POST /api/group/member (add member)
            if ((path.equals("/api/group/member") || path.equals("/api/member")) && method.equalsIgnoreCase("POST")) {
                String body = readRequestBody(exchange);
                String grpName = extractString(body, "group");
                Group g = groups.get(grpName);
                if (g == null && !groups.isEmpty()) g = groups.values().iterator().next();

                if (g != null) {
                    String memberName = extractString(body, "name");
                    if (memberName.isEmpty()) memberName = extractString(body, "member");
                    if (!memberName.isEmpty()) {
                        synchronized (g) {
                            if (!g.members.contains(memberName)) {
                                g.members.add(memberName);
                            }
                        }
                        sendResponse(exchange, 200, groupToJson(g), "application/json");
                        return;
                    }
                    sendResponse(exchange, 400, "{\"error\":\"Member name required\"}", "application/json");
                    return;
                }
                sendResponse(exchange, 400, "{\"error\":\"Invalid group\"}", "application/json");
                return;
            }

            // 3. REST API: GET /api/groups
            if (path.equals("/api/groups") && method.equalsIgnoreCase("GET")) {
                StringBuilder sb = new StringBuilder("[");
                int idx = 0;
                for (Group g : groups.values()) {
                    if (idx++ > 0) sb.append(",");
                    long totalSpend = 0;
                    for (Expense e : g.expenses) if (!e.settlement) totalSpend += e.total;
                    sb.append("{\"name\":").append(quote(g.name))
                      .append(",\"memberCount\":").append(g.members.size())
                      .append(",\"expenseCount\":").append(g.expenses.size())
                      .append(",\"totalSpend\":").append(totalSpend)
                      .append("}");
                }
                sb.append("]");
                sendResponse(exchange, 200, sb.toString(), "application/json");
                return;
            }

            // 4. REST API: POST /api/expense or /api/group/expense
            if ((path.equals("/api/expense") || path.equals("/api/group/expense")) && method.equalsIgnoreCase("POST")) {
                String body = readRequestBody(exchange);
                String grpName = extractString(body, "group");
                Group g = groups.get(grpName);
                if (g == null && !groups.isEmpty()) g = groups.values().iterator().next();

                if (g != null) {
                    String desc = extractString(body, "description");
                    String payer = extractString(body, "payer");
                    String cat = extractString(body, "category");
                    String date = extractString(body, "date");
                    String notes = extractString(body, "notes");
                    boolean settlement = extractBool(body, "settlement");
                    Map<String, Long> shares = extractShares(body);

                    if (desc.isEmpty()) desc = "Expense";
                    if (payer.isEmpty()) payer = "You";
                    if (shares.isEmpty()) {
                        long totalAmt = extractLong(body, "total");
                        long base = g.members.isEmpty() ? 0 : totalAmt / g.members.size();
                        for (String m : g.members) shares.put(m, base);
                    }

                    Expense newExp = new Expense(
                            "exp_" + System.currentTimeMillis(),
                            desc, payer, shares, settlement, cat, date, notes
                    );

                    synchronized (g) {
                        g.expenses.add(0, newExp);
                    }

                    sendResponse(exchange, 200, groupToJson(g), "application/json");
                    return;
                }
                sendResponse(exchange, 400, "{\"error\":\"Invalid group\"}", "application/json");
                return;
            }

            // 5. REST API: POST /api/settle or /api/group/settle
            if ((path.equals("/api/settle") || path.equals("/api/group/settle")) && method.equalsIgnoreCase("POST")) {
                String body = readRequestBody(exchange);
                String grpName = extractString(body, "group");
                Group g = groups.get(grpName);
                if (g == null && !groups.isEmpty()) g = groups.values().iterator().next();

                if (g != null) {
                    String from = extractString(body, "from");
                    String to = extractString(body, "to");
                    long amount = extractLong(body, "amount");
                    String methodPaid = extractString(body, "method");
                    String date = extractString(body, "date");
                    String notes = extractString(body, "notes");

                    if (from.isEmpty()) from = "Rahul";
                    if (to.isEmpty()) to = "You";
                    if (amount <= 0) amount = 10000;

                    Map<String, Long> shares = new LinkedHashMap<>();
                    shares.put(to, amount);

                    Expense settleExp = new Expense(
                            "settle_" + System.currentTimeMillis(),
                            from + " paid " + to + " " + money(amount),
                            from,
                            shares,
                            true,
                            "price_check",
                            date.isEmpty() ? "Oct 24, 2024" : date,
                            notes.isEmpty() ? methodPaid + " Payment recorded" : notes
                    );

                    synchronized (g) {
                        g.expenses.add(0, settleExp);
                    }

                    sendResponse(exchange, 200, groupToJson(g), "application/json");
                    return;
                }
                sendResponse(exchange, 400, "{\"error\":\"Invalid group\"}", "application/json");
                return;
            }

            // 6. REST API: POST or GET /api/reset (reset all expenses to 0)
            if (path.equals("/api/reset")) {
                for (Group g : groups.values()) {
                    synchronized (g) {
                        g.expenses.clear();
                    }
                }
                Group g = groups.isEmpty() ? null : groups.values().iterator().next();
                if (g != null) {
                    sendResponse(exchange, 200, groupToJson(g), "application/json");
                } else {
                    sendResponse(exchange, 200, "{\"status\":\"reset\"}", "application/json");
                }
                return;
            }

            // Fallback: 404
            sendResponse(exchange, 404, "{\"error\":\"Endpoint not found\"}", "application/json");
        }
    }

    // ------------------------------------------------------------- JSON Utilities

    public static String groupToJson(Group g) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"name\":").append(quote(g.name)).append(",");

        // members
        sb.append("\"members\":[");
        for (int i = 0; i < g.members.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(quote(g.members.get(i)));
        }
        sb.append("],");

        // net balances
        sb.append("\"netBalances\":{");
        Map<String, Long> net = g.netBalances();
        int count = 0;
        for (Map.Entry<String, Long> e : net.entrySet()) {
            if (count++ > 0) sb.append(",");
            sb.append(quote(e.getKey())).append(":").append(e.getValue());
        }
        sb.append("},");

        // simplified debts
        sb.append("\"simplifiedDebts\":[");
        List<String> debts = g.simplifiedDebts();
        for (int i = 0; i < debts.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(quote(debts.get(i)));
        }
        sb.append("],");

        // total spend
        long totalSpend = 0;
        for (Expense e : g.expenses) {
            if (!e.settlement) totalSpend += e.total;
        }
        sb.append("\"totalSpend\":").append(totalSpend).append(",");

        // expenses
        sb.append("\"expenses\":[");
        for (int i = 0; i < g.expenses.size(); i++) {
            if (i > 0) sb.append(",");
            Expense e = g.expenses.get(i);
            sb.append("{");
            sb.append("\"id\":").append(quote(e.id)).append(",");
            sb.append("\"description\":").append(quote(e.description)).append(",");
            sb.append("\"payer\":").append(quote(e.payer)).append(",");
            sb.append("\"total\":").append(e.total).append(",");
            sb.append("\"category\":").append(quote(e.category)).append(",");
            sb.append("\"date\":").append(quote(e.date)).append(",");
            sb.append("\"notes\":").append(quote(e.notes)).append(",");
            sb.append("\"settlement\":").append(e.settlement).append(",");
            sb.append("\"shares\":{");
            int sc = 0;
            for (Map.Entry<String, Long> s : e.shares.entrySet()) {
                if (sc++ > 0) sb.append(",");
                sb.append(quote(s.getKey())).append(":").append(s.getValue());
            }
            sb.append("}");
            sb.append("}");
        }
        sb.append("]");

        sb.append("}");
        return sb.toString();
    }

    private static String quote(String s) {
        if (s == null) return "\"\"";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "") + "\"";
    }

    private static String readRequestBody(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void sendResponse(HttpExchange exchange, int statusCode, String responseText, String contentType) throws IOException {
        byte[] bytes = responseText.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String extractString(String json, String key) {
        Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"");
        Matcher m = p.matcher(json);
        return m.find() ? m.group(1) : "";
    }

    private static long extractLong(String json, String key) {
        Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*(\\d+)");
        Matcher m = p.matcher(json);
        return m.find() ? Long.parseLong(m.group(1)) : 0L;
    }

    private static boolean extractBool(String json, String key) {
        Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*(true|false)");
        Matcher m = p.matcher(json);
        return m.find() && Boolean.parseBoolean(m.group(1));
    }

    private static Map<String, Long> extractShares(String json) {
        Map<String, Long> shares = new LinkedHashMap<>();
        int idx = json.indexOf("\"shares\"");
        if (idx != -1) {
            int start = json.indexOf('{', idx);
            int end = json.indexOf('}', start);
            if (start != -1 && end != -1) {
                String obj = json.substring(start + 1, end);
                Pattern p = Pattern.compile("\"([^\"]+)\"\\s*:\\s*(\\d+)");
                Matcher m = p.matcher(obj);
                while (m.find()) {
                    shares.put(m.group(1), Long.parseLong(m.group(2)));
                }
            }
        }
        return shares;
    }

    // ------------------------------------------------------------------ Main

    public static void main(String[] args) {
        initSeedData();

        // Check if user launched with --desktop for native desktop Swing GUI
        for (String arg : args) {
            if ("--desktop".equalsIgnoreCase(arg) || "--gui".equalsIgnoreCase(arg)) {
                SplitwiseDesktopGUI.launchGUI();
                return;
            }
        }

        // Start embedded HTTP server
        int port = startHttpServer();

        System.out.println("==========================================================");
        System.out.println("      SPLITWISE INDIA - FULLSTACK JAVA EDITION            ");
        System.out.println("==========================================================");
        if (port != -1) {
            String url = "http://localhost:" + port;
            System.out.println(" ✅ Web Server running at: " + url);
            System.out.println("    Serving UI: " + Paths.get("index.html").toAbsolutePath());
            System.out.println("    REST API available at: " + url + "/api/group");
            System.out.println("----------------------------------------------------------");
            System.out.println(" Tip: You can open " + url + " in any browser, or use the");
            System.out.println("      native desktop Swing GUI via: java SplitwiseDesktopGUI");
            System.out.println("==========================================================");

            // Attempt to open browser automatically
            try {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    Desktop.getDesktop().browse(new URI(url));
                }
            } catch (Exception ignored) {}
        } else {
            System.out.println(" ⚠️ Could not start HTTP server on ports 8080-8090.");
        }

        // Keep console interactive CLI running
        while (true) {
            System.out.println("\n--- MAIN MENU ---");
            System.out.println("1. Create group");
            System.out.println("2. Add member to group");
            System.out.println("3. Add expense");
            System.out.println("4. View balances");
            System.out.println("5. Settle up");
            System.out.println("6. View expense history");
            System.out.println("7. Launch Desktop GUI (Swing window)");
            System.out.println("0. Exit");
            int choice = readInt("Choose: ");

            switch (choice) {
                case 1: createGroup(); break;
                case 2: addMember(); break;
                case 3: addExpense(); break;
                case 4: viewBalances(); break;
                case 5: settleUp(); break;
                case 6: viewHistory(); break;
                case 7: SplitwiseDesktopGUI.launchGUI(); break;
                case 0:
                    System.out.println("Goodbye!");
                    System.exit(0);
                    return;
                default: System.out.println("Invalid option.");
            }
        }
    }

    // --------------------------------------------------------------- Actions

    static void createGroup() {
        String name = readLine("Group name: ");
        if (name.isEmpty() || groups.containsKey(name)) {
            System.out.println("Group name is empty or already exists.");
            return;
        }
        Group g = new Group(name);
        String line = readLine("Members (comma separated, e.g. Alice,Bob,Carol): ");
        for (String m : line.split(",")) {
            m = m.trim();
            if (!m.isEmpty() && !g.members.contains(m)) g.members.add(m);
        }
        if (g.members.size() < 2) {
            System.out.println("A group needs at least 2 members.");
            return;
        }
        groups.put(name, g);
        System.out.println("Group '" + name + "' created with: " + String.join(", ", g.members));
    }

    static void addMember() {
        Group g = pickGroup();
        if (g == null) return;
        String name = readLine("New member name: ");
        if (name.isEmpty() || g.members.contains(name)) {
            System.out.println("Name is empty or already in the group.");
            return;
        }
        g.members.add(name);
        System.out.println(name + " added to " + g.name);
    }

    static void addExpense() {
        Group g = pickGroup();
        if (g == null) return;

        String description = readLine("Description (e.g. Dinner): ");
        String payer = pickMember(g, "Who paid?");
        long total = readMoney("Total amount: ");
        if (total <= 0) {
            System.out.println("Amount must be positive.");
            return;
        }

        System.out.println("Split type:  1. Equally   2. Exact amounts   3. Percentages");
        int type = readInt("Choose: ");

        Map<String, Long> shares;
        switch (type) {
            case 1: shares = splitEqually(g, total); break;
            case 2: shares = splitExact(g, total); break;
            case 3: shares = splitPercent(g, total); break;
            default:
                System.out.println("Invalid split type.");
                return;
        }
        if (shares == null) return;

        g.expenses.add(0, new Expense(description, payer, shares, false));
        System.out.println("Expense added: " + description + " (" + money(total) + ") paid by " + payer);
        for (Map.Entry<String, Long> s : shares.entrySet()) {
            System.out.println("   " + s.getKey() + " owes " + money(s.getValue()));
        }
    }

    static void viewBalances() {
        Group g = pickGroup();
        if (g == null) return;

        System.out.println("\n=== Balances for " + g.name + " ===");
        for (Map.Entry<String, Long> e : g.netBalances().entrySet()) {
            long v = e.getValue();
            if (v > 0)      System.out.println(e.getKey() + " is owed " + money(v));
            else if (v < 0) System.out.println(e.getKey() + " owes " + money(-v));
            else            System.out.println(e.getKey() + " is settled up");
        }

        List<String> debts = g.simplifiedDebts();
        System.out.println("\n--- Simplified payments ---");
        if (debts.isEmpty()) System.out.println("Everyone is settled up!");
        else debts.forEach(System.out::println);
    }

    static void settleUp() {
        Group g = pickGroup();
        if (g == null) return;

        String from = pickMember(g, "Who is paying?");
        String to = pickMember(g, "Who is being paid?");
        if (from.equals(to)) {
            System.out.println("Payer and receiver must be different.");
            return;
        }
        long amount = readMoney("Amount paid: ");
        if (amount <= 0) {
            System.out.println("Amount must be positive.");
            return;
        }
        Map<String, Long> shares = new LinkedHashMap<>();
        shares.put(to, amount);
        g.expenses.add(0, new Expense(from + " paid " + to, from, shares, true));
        System.out.println("Recorded: " + from + " paid " + to + " " + money(amount));
    }

    static void viewHistory() {
        Group g = pickGroup();
        if (g == null) return;

        System.out.println("\n=== History for " + g.name + " ===");
        if (g.expenses.isEmpty()) {
            System.out.println("No expenses yet.");
            return;
        }
        int i = 1;
        for (Expense e : g.expenses) {
            if (e.settlement) {
                System.out.println(i++ + ". [SETTLEMENT] " + e.description + "  " + money(e.total));
            } else {
                System.out.println(i++ + ". " + e.description + "  " + money(e.total)
                        + "  (paid by " + e.payer + ")");
                for (Map.Entry<String, Long> s : e.shares.entrySet()) {
                    System.out.println("      " + s.getKey() + ": " + money(s.getValue()));
                }
            }
        }
    }

    // ---------------------------------------------------------- Split logic

    static Map<String, Long> splitEqually(Group g, long total) {
        List<String> people = pickParticipants(g);
        if (people.isEmpty()) return null;

        long base = total / people.size();
        long remainder = total % people.size();
        Map<String, Long> shares = new LinkedHashMap<>();
        for (int i = 0; i < people.size(); i++) {
            shares.put(people.get(i), base + (i < remainder ? 1 : 0));
        }
        return shares;
    }

    static Map<String, Long> splitExact(Group g, long total) {
        Map<String, Long> shares = new LinkedHashMap<>();
        long sum = 0;
        for (String m : g.members) {
            long amt = readMoney("  " + m + "'s share (0 to skip): ");
            if (amt > 0) {
                shares.put(m, amt);
                sum += amt;
            }
        }
        if (sum != total) {
            System.out.println("Shares add up to " + money(sum) + " but total is " + money(total) + ". Cancelled.");
            return null;
        }
        return shares;
    }

    static Map<String, Long> splitPercent(Group g, long total) {
        Map<String, Double> percents = new LinkedHashMap<>();
        double sum = 0;
        for (String m : g.members) {
            double p = readDouble("  " + m + "'s percent (0 to skip): ");
            if (p > 0) {
                percents.put(m, p);
                sum += p;
            }
        }
        if (Math.abs(sum - 100.0) > 0.001) {
            System.out.println("Percentages add up to " + sum + " instead of 100. Cancelled.");
            return null;
        }
        Map<String, Long> shares = new LinkedHashMap<>();
        long assigned = 0;
        String last = null;
        for (Map.Entry<String, Double> e : percents.entrySet()) {
            long amt = Math.round(total * e.getValue() / 100.0);
            shares.put(e.getKey(), amt);
            assigned += amt;
            last = e.getKey();
        }
        if (last != null) {
            shares.merge(last, total - assigned, Long::sum);
        }
        return shares;
    }

    // ------------------------------------------------------------- Pickers

    static Group pickGroup() {
        if (groups.isEmpty()) {
            System.out.println("No groups yet. Create one first.");
            return null;
        }
        List<Group> list = new ArrayList<>(groups.values());
        System.out.println("Select a group:");
        for (int i = 0; i < list.size(); i++) {
            System.out.println("  " + (i + 1) + ". " + list.get(i).name
                    + " (" + String.join(", ", list.get(i).members) + ")");
        }
        int idx = readInt("Choose: ");
        if (idx < 1 || idx > list.size()) {
            System.out.println("Invalid group.");
            return null;
        }
        return list.get(idx - 1);
    }

    static String pickMember(Group g, String prompt) {
        while (true) {
            System.out.println(prompt);
            for (int i = 0; i < g.members.size(); i++) {
                System.out.println("  " + (i + 1) + ". " + g.members.get(i));
            }
            int idx = readInt("Choose: ");
            if (idx >= 1 && idx <= g.members.size()) return g.members.get(idx - 1);
            System.out.println("Invalid choice, try again.");
        }
    }

    static List<String> pickParticipants(Group g) {
        System.out.println("Split between whom?");
        for (int i = 0; i < g.members.size(); i++) {
            System.out.println("  " + (i + 1) + ". " + g.members.get(i));
        }
        String line = readLine("Numbers separated by commas, or 'all': ");
        List<String> people = new ArrayList<>();
        if (line.equalsIgnoreCase("all")) {
            people.addAll(g.members);
            return people;
        }
        for (String part : line.split(",")) {
            try {
                int idx = Integer.parseInt(part.trim());
                String m = g.members.get(idx - 1);
                if (!people.contains(m)) people.add(m);
            } catch (Exception ex) {
                System.out.println("Invalid selection: '" + part.trim() + "'");
                return new ArrayList<>();
            }
        }
        return people;
    }

    // ------------------------------------------------------------- Helpers

    public static String money(long cents) {
        return String.format("₹%,.2f", cents / 100.0);
    }

    static String readLine(String prompt) {
        System.out.print(prompt);
        return scanner.hasNextLine() ? scanner.nextLine().trim() : "";
    }

    static int readInt(String prompt) {
        while (true) {
            String s = readLine(prompt);
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException e) {
                System.out.println("Please enter a whole number.");
                if (!scanner.hasNextLine()) return 0;
            }
        }
    }

    static double readDouble(String prompt) {
        while (true) {
            String s = readLine(prompt);
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException e) {
                System.out.println("Please enter a number.");
                if (!scanner.hasNextLine()) return 0;
            }
        }
    }

    static long readMoney(String prompt) {
        return Math.round(readDouble(prompt) * 100);
    }
}
