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
 * ============================================================================
 * SPLITWISE INDIA - FULLSTACK ZERO-DEPENDENCY EXPENSE SHARING APPLICATION
 * ============================================================================
 *
 * Core Architecture & Features:
 * ----------------------------------------------------------------------------
 * 1. Data Models:
 *    - `Expense`: Represents an individual expense or settlement item.
 *                 Handles multi-party split shares, payer attribution, categories,
 *                 and audit timestamps.
 *    - `Group`:   Maintains group members, expense ledger, and synchronization.
 *                 Calculates net member balances and executes debt simplification.
 *
 * 2. Debt Simplification Algorithm:
 *    - Greedy minimum cash-flow optimizer that condenses complex cyclic group
 *      debts into the minimal number of direct settlements.
 *
 * 3. Embedded REST HTTP Server:
 *    - Built using standard library `HttpServer` (zero external dependencies).
 *    - Serves dynamic Single-Page Application (SPA) HTML5/CSS assets.
 *    - Exposes complete JSON REST API endpoints (/api/group, /api/expense, etc.).
 *
 * 4. Multi-Interface Channels:
 *    - Interactive Terminal CLI for rapid console management.
 *    - Native Java Swing Desktop UI (`SplitwiseDesktopGUI`).
 *    - Web Application accessed via standard browser.
 *
 * 5. Accurate Financial Accounting:
 *    - All monetary computations stored as exact integer values (paise/cents)
 *      to eliminate IEEE 754 floating-point inaccuracies.
 * ============================================================================
 */
public class SplitwiseApp {

    // =========================================================================
    // FEATURE 1: DOMAIN MODELS
    // =========================================================================

    /**
     * Represents a single transaction in a group ledger.
     * Can represent either an expense (split among participants) or a settlement
     * payment (money transferred between members to settle debts).
     */
    public static class Expense {
        public final String id;                        // Unique identifier (exp_...)
        public final String description;               // Purpose of payment (e.g. "Dinner")
        public final String payer;                     // Name of the member who paid
        public final long total;                       // Total transaction amount in paise (1 INR = 100 paise)
        public final Map<String, Long> shares;         // Member name -> amount owed in paise
        public final boolean settlement;               // True if this is a direct debt settlement transfer
        public final String category;                  // Category tag (food, transport, groceries, etc.)
        public final String date;                      // Formatted date string
        public final String notes;                     // Optional receipt or payment notes

        /**
         * Convenience constructor for basic expense creation with defaults.
         */
        public Expense(String description, String payer, Map<String, Long> shares, boolean settlement) {
            this("exp_" + System.currentTimeMillis() + "_" + (int) (Math.random() * 1000),
                 description, payer, shares, settlement, "restaurant", "Oct 24, 2024", "");
        }

        /**
         * Full parameter constructor for granular expense configuration.
         */
        public Expense(String id, String description, String payer, Map<String, Long> shares,
                       boolean settlement, String category, String date, String notes) {
            this.id = (id != null && !id.trim().isEmpty()) ? id : "exp_" + System.currentTimeMillis();
            this.description = (description != null && !description.trim().isEmpty()) ? description : "Expense";
            this.payer = payer;
            this.shares = new LinkedHashMap<>(shares);
            this.settlement = settlement;
            this.category = (category != null && !category.trim().isEmpty()) ? category : "restaurant";
            this.date = (date != null && !date.trim().isEmpty()) ? date : "Oct 24, 2024";
            this.notes = (notes != null) ? notes : "";

            // Compute total amount as the exact sum of all member shares
            long sum = 0;
            for (long s : shares.values()) {
                sum += s;
            }
            this.total = sum;
        }
    }

    /**
     * Represents a collaborative expense group (e.g., "Goa Trip", "Apartment").
     * Manages group members, registered expenses, and calculates net financial balances.
     */
    public static class Group {
        public String name;
        public final List<String> members = new ArrayList<>();
        public final List<Expense> expenses = new ArrayList<>();

        public Group(String name) {
            this.name = name;
        }

        /**
         * Computes the net balance for each member in the group.
         * Positive value indicates the member is owed money.
         * Negative value indicates the member owes money.
         *
         * Formula for each member:
         *   Net = Total amount paid by member - Total shares owed across all expenses
         */
        public synchronized Map<String, Long> netBalances() {
            Map<String, Long> net = new LinkedHashMap<>();
            for (String member : members) {
                net.put(member, 0L);
            }

            for (Expense expense : expenses) {
                // Credit the payer
                net.merge(expense.payer, expense.total, Long::sum);

                // Debit each participant's share
                for (Map.Entry<String, Long> share : expense.shares.entrySet()) {
                    net.merge(share.getKey(), -share.getValue(), Long::sum);
                }
            }
            return net;
        }

        /**
         * FEATURE 2: GREEDY DEBT SIMPLIFICATION ALGORITHM
         * ---------------------------------------------------------------------
         * Reduces the total number of transactions needed to settle all debts.
         * Iteratively pairs the largest debtor with the largest creditor until
         * all balances are zeroed.
         *
         * @return Formatted human-readable settlement transactions.
         */
        public synchronized List<String> simplifiedDebts() {
            Map<String, Long> balanceMap = netBalances();
            List<String> settlements = new ArrayList<>();

            while (true) {
                String maxCreditor = null;
                String maxDebtor = null;

                // Identify member who is owed the most and member who owes the most
                for (Map.Entry<String, Long> entry : balanceMap.entrySet()) {
                    if (maxCreditor == null || entry.getValue() > balanceMap.get(maxCreditor)) {
                        maxCreditor = entry.getKey();
                    }
                    if (maxDebtor == null || entry.getValue() < balanceMap.get(maxDebtor)) {
                        maxDebtor = entry.getKey();
                    }
                }

                // If no remaining debts or credits exist, all accounts are settled
                if (maxCreditor == null || balanceMap.get(maxCreditor) <= 0 || balanceMap.get(maxDebtor) >= 0) {
                    break;
                }

                // Settle the minimum of what is owed vs what is due
                long transferAmount = Math.min(balanceMap.get(maxCreditor), -balanceMap.get(maxDebtor));
                if (transferAmount <= 0) {
                    break;
                }

                settlements.add(maxDebtor + " owes " + maxCreditor + "  " + formatCurrency(transferAmount));
                balanceMap.merge(maxCreditor, -transferAmount, Long::sum);
                balanceMap.merge(maxDebtor, transferAmount, Long::sum);
            }
            return settlements;
        }
    }

    // =========================================================================
    // FEATURE 3: APPLICATION STATE & INITIAL SEED DATA
    // =========================================================================

    private static final Scanner SCANNER = new Scanner(System.in);
    public static final Map<String, Group> groups = new LinkedHashMap<>();
    private static int activeServerPort = 8080;

    /**
     * Seeds initial groups, realistic members, and categorized expenses
     * to provide an immediate out-of-the-box demonstration.
     */
    public static void initSeedData() {
        if (!groups.isEmpty()) return;

        // Group 1: Goa Vacation Trip
        Group goa = new Group("Goa Trip");
        goa.members.addAll(Arrays.asList("Rahul", "Priya", "Amit", "You"));

        Map<String, Long> cabShares = createEqualShares(Arrays.asList("Rahul", "Priya", "Amit", "You"), 35000L);
        goa.expenses.add(new Expense("exp_cab_1", "Airport Cab - Mopa to Candolim", "Rahul", cabShares, false, "transport", "Oct 21, 2024", "Prepaid taxi receipt #TX-882"));

        Map<String, Long> blinkitShares = createEqualShares(Arrays.asList("Rahul", "Priya", "Amit", "You"), 46000L);
        goa.expenses.add(new Expense("exp_groceries_1", "Blinkit - Snacks and Water", "You", blinkitShares, false, "groceries", "Oct 22, 2024", "Order #BL-99231"));

        Map<String, Long> chaiShares = createEqualShares(Arrays.asList("Rahul", "Priya", "Amit", "You"), 9000L);
        goa.expenses.add(new Expense("exp_chai_1", "Beach Shack Breakfast and Tea", "Amit", chaiShares, false, "food", "Oct 22, 2024", "Bill paid by cash"));

        Map<String, Long> scubaShares = createEqualShares(Arrays.asList("Rahul", "Priya", "Amit", "You"), 175000L);
        goa.expenses.add(new Expense("exp_scuba_1", "Scuba Diving at Grande Island", "Rahul", scubaShares, false, "entertainment", "Oct 23, 2024", "Includes gear and boat transfer"));

        Map<String, Long> dinnerShares = createEqualShares(Arrays.asList("Rahul", "Priya", "Amit", "You"), 132000L);
        goa.expenses.add(new Expense("exp_dinner_1", "Fishermans Wharf Dinner", "Priya", dinnerShares, false, "food", "Oct 23, 2024", "Table of 4"));

        Map<String, Long> fuelShares = createEqualShares(Arrays.asList("Rahul", "Priya", "Amit", "You"), 30000L);
        goa.expenses.add(new Expense("exp_fuel_1", "Scooter Rental and Petrol", "You", fuelShares, false, "transport", "Oct 24, 2024", "2 Honda Activas for 3 days"));

        groups.put(goa.name, goa);

        // Group 2: Apartment Flatmates
        Group flat = new Group("Koramangala Flat");
        flat.members.addAll(Arrays.asList("Rohan", "Sneha", "Karan", "You"));

        Map<String, Long> rentShares = createEqualShares(Arrays.asList("Rohan", "Sneha", "Karan", "You"), 1250000L);
        flat.expenses.add(new Expense("exp_rent_1", "October Flat Rent and Maintenance", "You", rentShares, false, "rent", "Oct 05, 2024", "Transferred via NEFT to landlord"));

        Map<String, Long> wifiShares = createEqualShares(Arrays.asList("Rohan", "Sneha", "Karan", "You"), 37500L);
        flat.expenses.add(new Expense("exp_wifi_1", "ACT Fibernet Broadband 300Mbps", "Sneha", wifiShares, false, "bills", "Oct 10, 2024", "Account #ACT-44910"));

        Map<String, Long> cookShares = createEqualShares(Arrays.asList("Rohan", "Sneha", "Karan", "You"), 200000L);
        flat.expenses.add(new Expense("exp_cook_1", "Monthly Cook Salary (Savitri Bai)", "Rohan", cookShares, false, "services", "Oct 15, 2024", "Paid via GPay"));

        Map<String, Long> zeptoShares = createEqualShares(Arrays.asList("Rohan", "Sneha", "Karan", "You"), 32000L);
        flat.expenses.add(new Expense("exp_zepto_1", "Zepto - Cleaning Supplies and Oil", "You", zeptoShares, false, "groceries", "Oct 18, 2024", "Detergent, dishwash, olive oil"));

        groups.put(flat.name, flat);

        // Group 3: Office Lunches & Tea
        Group office = new Group("Office Lunches");
        office.members.addAll(Arrays.asList("Vikram", "Ananya", "Nikhil", "You"));

        Map<String, Long> lunchShares = createEqualShares(Arrays.asList("Vikram", "Ananya", "Nikhil", "You"), 42000L);
        office.expenses.add(new Expense("exp_lunch_1", "Truffles Burgers and Pasta", "Vikram", lunchShares, false, "food", "Oct 22, 2024", "Friday team lunch"));

        Map<String, Long> chaiOfficeShares = createEqualShares(Arrays.asList("Vikram", "Ananya", "Nikhil", "You"), 12000L);
        office.expenses.add(new Expense("exp_chai_off", "Chai Point - Ginger Chai Flask", "Ananya", chaiOfficeShares, false, "food", "Oct 23, 2024", "Evening tea break"));

        groups.put(office.name, office);
    }

    private static Map<String, Long> createEqualShares(List<String> members, long sharePerMember) {
        Map<String, Long> shares = new LinkedHashMap<>();
        for (String member : members) {
            shares.put(member, sharePerMember);
        }
        return shares;
    }

    // =========================================================================
    // FEATURE 4: EMBEDDED HTTP SERVER & REST API
    // =========================================================================

    /**
     * Initializes and starts an embedded HTTP server on ports 8080 through 8090.
     * Automatically binds to the first available port.
     *
     * @return Bound port number, or -1 if all candidate ports are busy.
     */
    public static int startHttpServer() {
        int candidatePort = 8080;
        while (candidatePort <= 8090) {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress(candidatePort), 0);
                server.createContext("/", new StaticAndApiHandler());
                server.setExecutor(Executors.newCachedThreadPool());
                server.start();
                activeServerPort = candidatePort;
                return candidatePort;
            } catch (IOException e) {
                candidatePort++;
            }
        }
        return -1;
    }

    /**
     * Unified HTTP Request Handler:
     * - Delivers static Single Page Application (SPA) HTML assets.
     * - Serves RESTful endpoints for CRUD on groups, members, expenses, and settlements.
     */
    static class StaticAndApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();

            // 1. CORS Preflight Handling
            if (method.equalsIgnoreCase("OPTIONS")) {
                sendResponse(exchange, 204, "", "text/plain");
                return;
            }

            // 2. Static Asset Delivery: serves index.html at root
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

            // 3. REST API: POST /api/group -> Create group
            if (path.equals("/api/group") && method.equalsIgnoreCase("POST")) {
                handleCreateGroup(exchange);
                return;
            }

            // 4. REST API: POST /api/group/rename -> Rename group
            if ((path.equals("/api/group/rename") || path.equals("/api/rename-group")) && method.equalsIgnoreCase("POST")) {
                handleRenameGroup(exchange);
                return;
            }

            // 5. REST API: POST or DELETE /api/group/delete -> Delete group
            if (path.equals("/api/group/delete") || path.equals("/api/delete-group") ||
               (path.equals("/api/group") && method.equalsIgnoreCase("DELETE"))) {
                handleDeleteGroup(exchange, method);
                return;
            }

            // 6. REST API: GET /api/group?name=... -> Retrieve group details
            if (path.equals("/api/group") && method.equalsIgnoreCase("GET")) {
                handleGetGroup(exchange);
                return;
            }

            // 7. REST API: POST /api/group/member -> Add member to group
            if ((path.equals("/api/group/member") || path.equals("/api/member")) && method.equalsIgnoreCase("POST")) {
                handleAddMember(exchange);
                return;
            }

            // 8. REST API: GET /api/groups -> List all groups summary
            if (path.equals("/api/groups") && method.equalsIgnoreCase("GET")) {
                handleListGroups(exchange);
                return;
            }

            // 9. REST API: POST /api/expense -> Record an expense
            if ((path.equals("/api/expense") || path.equals("/api/group/expense")) && method.equalsIgnoreCase("POST")) {
                handleAddExpense(exchange);
                return;
            }

            // 10. REST API: POST /api/settle -> Record a debt settlement
            if ((path.equals("/api/settle") || path.equals("/api/group/settle")) && method.equalsIgnoreCase("POST")) {
                handleSettleDebt(exchange);
                return;
            }

            // 11. REST API: /api/reset -> Clear group expenses
            if (path.equals("/api/reset")) {
                handleResetExpenses(exchange);
                return;
            }

            // Fallback: 404 Not Found
            sendResponse(exchange, 404, "{\"error\":\"Endpoint not found\"}", "application/json");
        }

        private void handleCreateGroup(HttpExchange exchange) throws IOException {
            String body = readRequestBody(exchange);
            String name = extractString(body, "name");
            if (name.isEmpty()) name = extractString(body, "group");

            if (!name.isEmpty()) {
                synchronized (groups) {
                    Group group = groups.computeIfAbsent(name, Group::new);
                    if (!group.members.contains("You")) {
                        group.members.add("You");
                    }
                    sendResponse(exchange, 200, groupToJson(group), "application/json");
                }
                return;
            }
            sendResponse(exchange, 400, "{\"error\":\"Missing group name\"}", "application/json");
        }

        private void handleRenameGroup(HttpExchange exchange) throws IOException {
            String body = readRequestBody(exchange);
            String oldName = extractString(body, "oldName");
            if (oldName.isEmpty()) oldName = extractString(body, "group");
            String newName = extractString(body, "newName");
            if (newName.isEmpty()) newName = extractString(body, "name");

            if (!oldName.isEmpty() && !newName.isEmpty()) {
                synchronized (groups) {
                    Group group = groups.remove(oldName);
                    if (group != null) {
                        group.name = newName;
                        groups.put(newName, group);
                        sendResponse(exchange, 200, groupToJson(group), "application/json");
                        return;
                    }
                }
                sendResponse(exchange, 404, "{\"error\":\"Group not found\"}", "application/json");
                return;
            }
            sendResponse(exchange, 400, "{\"error\":\"Missing oldName or newName\"}", "application/json");
        }

        private void handleDeleteGroup(HttpExchange exchange, String method) throws IOException {
            String name = "";
            if (method.equalsIgnoreCase("POST")) {
                String body = readRequestBody(exchange);
                name = extractString(body, "name");
                if (name.isEmpty()) name = extractString(body, "group");
            }
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
                        Group defaultGroup = new Group("Personal Expenses");
                        defaultGroup.members.add("You");
                        groups.put(defaultGroup.name, defaultGroup);
                        sendResponse(exchange, 200, groupToJson(defaultGroup), "application/json");
                    }
                }
                return;
            }
            sendResponse(exchange, 400, "{\"error\":\"Missing group name to delete\"}", "application/json");
        }

        private void handleGetGroup(HttpExchange exchange) throws IOException {
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

            Group group = groups.get(groupName);
            if (group == null && !groups.isEmpty()) {
                group = groups.values().iterator().next();
            }
            if (group != null) {
                sendResponse(exchange, 200, groupToJson(group), "application/json");
            } else {
                sendResponse(exchange, 404, "{\"error\":\"Group not found\"}", "application/json");
            }
        }

        private void handleAddMember(HttpExchange exchange) throws IOException {
            String body = readRequestBody(exchange);
            String grpName = extractString(body, "group");
            Group group = groups.get(grpName);
            if (group == null && !groups.isEmpty()) group = groups.values().iterator().next();

            if (group != null) {
                String memberName = extractString(body, "name");
                if (memberName.isEmpty()) memberName = extractString(body, "member");
                if (!memberName.isEmpty()) {
                    synchronized (group) {
                        if (!group.members.contains(memberName)) {
                            group.members.add(memberName);
                        }
                    }
                    sendResponse(exchange, 200, groupToJson(group), "application/json");
                    return;
                }
                sendResponse(exchange, 400, "{\"error\":\"Member name required\"}", "application/json");
                return;
            }
            sendResponse(exchange, 400, "{\"error\":\"Invalid group\"}", "application/json");
        }

        private void handleListGroups(HttpExchange exchange) throws IOException {
            StringBuilder sb = new StringBuilder("[");
            int idx = 0;
            for (Group group : groups.values()) {
                if (idx++ > 0) sb.append(",");
                long totalSpend = 0;
                for (Expense e : group.expenses) {
                    if (!e.settlement) totalSpend += e.total;
                }
                sb.append("{\"name\":").append(quote(group.name))
                  .append(",\"memberCount\":").append(group.members.size())
                  .append(",\"expenseCount\":").append(group.expenses.size())
                  .append(",\"totalSpend\":").append(totalSpend)
                  .append("}");
            }
            sb.append("]");
            sendResponse(exchange, 200, sb.toString(), "application/json");
        }

        private void handleAddExpense(HttpExchange exchange) throws IOException {
            String body = readRequestBody(exchange);
            String grpName = extractString(body, "group");
            Group group = groups.get(grpName);
            if (group == null && !groups.isEmpty()) group = groups.values().iterator().next();

            if (group != null) {
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
                    long base = group.members.isEmpty() ? 0 : totalAmt / group.members.size();
                    for (String m : group.members) shares.put(m, base);
                }

                Expense newExp = new Expense(
                        "exp_" + System.currentTimeMillis(),
                        desc, payer, shares, settlement, cat, date, notes
                );

                synchronized (group) {
                    group.expenses.add(0, newExp);
                }

                sendResponse(exchange, 200, groupToJson(group), "application/json");
                return;
            }
            sendResponse(exchange, 400, "{\"error\":\"Invalid group\"}", "application/json");
        }

        private void handleSettleDebt(HttpExchange exchange) throws IOException {
            String body = readRequestBody(exchange);
            String grpName = extractString(body, "group");
            Group group = groups.get(grpName);
            if (group == null && !groups.isEmpty()) group = groups.values().iterator().next();

            if (group != null) {
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
                        from + " paid " + to + " " + formatCurrency(amount),
                        from,
                        shares,
                        true,
                        "price_check",
                        date.isEmpty() ? "Oct 24, 2024" : date,
                        notes.isEmpty() ? methodPaid + " Payment recorded" : notes
                );

                synchronized (group) {
                    group.expenses.add(0, settleExp);
                }

                sendResponse(exchange, 200, groupToJson(group), "application/json");
                return;
            }
            sendResponse(exchange, 400, "{\"error\":\"Invalid group\"}", "application/json");
        }

        private void handleResetExpenses(HttpExchange exchange) throws IOException {
            for (Group group : groups.values()) {
                synchronized (group) {
                    group.expenses.clear();
                }
            }
            Group group = groups.isEmpty() ? null : groups.values().iterator().next();
            if (group != null) {
                sendResponse(exchange, 200, groupToJson(group), "application/json");
            } else {
                sendResponse(exchange, 200, "{\"status\":\"reset\"}", "application/json");
            }
        }
    }

    // =========================================================================
    // FEATURE 5: LIGHTWEIGHT ZERO-DEPENDENCY JSON SERIALIZER & PARSER
    // =========================================================================

    /**
     * Serializes a Group object and its ledger into JSON format for Web & GUI clients.
     */
    public static String groupToJson(Group g) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"name\":").append(quote(g.name)).append(",");

        // 1. Members array
        sb.append("\"members\":[");
        for (int i = 0; i < g.members.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(quote(g.members.get(i)));
        }
        sb.append("],");

        // 2. Member net balances map
        sb.append("\"netBalances\":{");
        Map<String, Long> net = g.netBalances();
        int count = 0;
        for (Map.Entry<String, Long> e : net.entrySet()) {
            if (count++ > 0) sb.append(",");
            sb.append(quote(e.getKey())).append(":").append(e.getValue());
        }
        sb.append("},");

        // 3. Simplified debt settlements list
        sb.append("\"simplifiedDebts\":[");
        List<String> debts = g.simplifiedDebts();
        for (int i = 0; i < debts.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(quote(debts.get(i)));
        }
        sb.append("],");

        // 4. Group total expenditure (excluding direct settlements)
        long totalSpend = 0;
        for (Expense e : g.expenses) {
            if (!e.settlement) totalSpend += e.total;
        }
        sb.append("\"totalSpend\":").append(totalSpend).append(",");

        // 5. Complete expenses ledger
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
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS, DELETE");
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

    // =========================================================================
    // FEATURE 6: CLI & APPLICATION ENTRY POINT
    // =========================================================================

    public static void main(String[] args) {
        // Initialize default demo groups and expenses
        initSeedData();

        // Support desktop launcher flags: java SplitwiseApp --desktop
        for (String arg : args) {
            if ("--desktop".equalsIgnoreCase(arg) || "--gui".equalsIgnoreCase(arg)) {
                SplitwiseDesktopGUI.launchGUI();
                return;
            }
        }

        // Start embedded web server
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

            // Attempt to open the default system browser automatically
            try {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    Desktop.getDesktop().browse(new URI(url));
                }
            } catch (Exception ignored) {}
        } else {
            System.out.println(" ⚠️ Could not start HTTP server on ports 8080-8090.");
        }

        // Run interactive command-line interface (CLI)
        runCliMenu();
    }

    /**
     * Interactive terminal navigation loop for managing groups without a browser.
     */
    private static void runCliMenu() {
        while (true) {
            System.out.println("\n--- MAIN MENU ---");
            System.out.println("1. Create group");
            System.out.println("2. Add member to group");
            System.out.println("3. Add expense");
            System.out.println("4. View balances & simplified debts");
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
                    System.out.println("Exiting Splitwise. Have a great day!");
                    System.exit(0);
                    return;
                default:
                    System.out.println("Invalid option. Please choose between 0 and 7.");
            }
        }
    }

    // =========================================================================
    // FEATURE 7: CLI INTERACTIVE OPERATIONS
    // =========================================================================

    static void createGroup() {
        String name = readLine("Group name: ");
        if (name.isEmpty() || groups.containsKey(name)) {
            System.out.println("Group name is empty or already exists.");
            return;
        }
        Group group = new Group(name);
        String line = readLine("Members (comma separated, e.g. Alice,Bob,Carol): ");
        for (String m : line.split(",")) {
            m = m.trim();
            if (!m.isEmpty() && !group.members.contains(m)) {
                group.members.add(m);
            }
        }
        if (group.members.size() < 2) {
            System.out.println("A group needs at least 2 members.");
            return;
        }
        groups.put(name, group);
        System.out.println("Group '" + name + "' created with: " + String.join(", ", group.members));
    }

    static void addMember() {
        Group group = pickGroup();
        if (group == null) return;
        String name = readLine("New member name: ");
        if (name.isEmpty() || group.members.contains(name)) {
            System.out.println("Name is empty or already in the group.");
            return;
        }
        group.members.add(name);
        System.out.println(name + " added to " + group.name);
    }

    static void addExpense() {
        Group group = pickGroup();
        if (group == null) return;

        String description = readLine("Description (e.g. Dinner): ");
        String payer = pickMember(group, "Who paid?");
        long total = readMoney("Total amount: ");
        if (total <= 0) {
            System.out.println("Amount must be positive.");
            return;
        }

        System.out.println("Split type:  1. Equally   2. Exact amounts   3. Percentages");
        int type = readInt("Choose: ");

        Map<String, Long> shares;
        switch (type) {
            case 1: shares = splitEqually(group, total); break;
            case 2: shares = splitExact(group, total); break;
            case 3: shares = splitPercent(group, total); break;
            default:
                System.out.println("Invalid split type.");
                return;
        }
        if (shares == null) return;

        group.expenses.add(0, new Expense(description, payer, shares, false));
        System.out.println("Expense added: " + description + " (" + formatCurrency(total) + ") paid by " + payer);
        for (Map.Entry<String, Long> s : shares.entrySet()) {
            System.out.println("   " + s.getKey() + " owes " + formatCurrency(s.getValue()));
        }
    }

    static void viewBalances() {
        Group group = pickGroup();
        if (group == null) return;

        System.out.println("\n=== Balances for " + group.name + " ===");
        for (Map.Entry<String, Long> e : group.netBalances().entrySet()) {
            long val = e.getValue();
            if (val > 0)      System.out.println(e.getKey() + " is owed " + formatCurrency(val));
            else if (val < 0) System.out.println(e.getKey() + " owes " + formatCurrency(-val));
            else              System.out.println(e.getKey() + " is settled up");
        }

        List<String> debts = group.simplifiedDebts();
        System.out.println("\n--- Simplified payments ---");
        if (debts.isEmpty()) System.out.println("Everyone is settled up!");
        else debts.forEach(System.out::println);
    }

    static void settleUp() {
        Group group = pickGroup();
        if (group == null) return;

        String from = pickMember(group, "Who is paying?");
        String to = pickMember(group, "Who is being paid?");
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
        group.expenses.add(0, new Expense(from + " paid " + to, from, shares, true));
        System.out.println("Recorded: " + from + " paid " + to + " " + formatCurrency(amount));
    }

    static void viewHistory() {
        Group group = pickGroup();
        if (group == null) return;

        System.out.println("\n=== History for " + group.name + " ===");
        if (group.expenses.isEmpty()) {
            System.out.println("No expenses yet.");
            return;
        }
        int i = 1;
        for (Expense e : group.expenses) {
            if (e.settlement) {
                System.out.println(i++ + ". [SETTLEMENT] " + e.description + "  " + formatCurrency(e.total));
            } else {
                System.out.println(i++ + ". " + e.description + "  " + formatCurrency(e.total)
                        + "  (paid by " + e.payer + ")");
                for (Map.Entry<String, Long> s : e.shares.entrySet()) {
                    System.out.println("      " + s.getKey() + ": " + formatCurrency(s.getValue()));
                }
            }
        }
    }

    // =========================================================================
    // FEATURE 8: EXPENSE SPLITTING STRATEGIES
    // =========================================================================

    /**
     * Splits an expense evenly among chosen participants, accounting for fractional cents.
     */
    static Map<String, Long> splitEqually(Group group, long total) {
        List<String> people = pickParticipants(group);
        if (people.isEmpty()) return null;

        long baseShare = total / people.size();
        long remainder = total % people.size();
        Map<String, Long> shares = new LinkedHashMap<>();
        for (int i = 0; i < people.size(); i++) {
            shares.put(people.get(i), baseShare + (i < remainder ? 1 : 0));
        }
        return shares;
    }

    /**
     * Splits an expense by custom exact monetary amounts per participant.
     */
    static Map<String, Long> splitExact(Group group, long total) {
        Map<String, Long> shares = new LinkedHashMap<>();
        long runningSum = 0;
        for (String member : group.members) {
            long amt = readMoney("  " + member + "'s share (0 to skip): ");
            if (amt > 0) {
                shares.put(member, amt);
                runningSum += amt;
            }
        }
        if (runningSum != total) {
            System.out.println("Shares sum to " + formatCurrency(runningSum) + " but total is " + formatCurrency(total) + ". Operation cancelled.");
            return null;
        }
        return shares;
    }

    /**
     * Splits an expense based on percentage shares per participant.
     */
    static Map<String, Long> splitPercent(Group group, long total) {
        Map<String, Double> percents = new LinkedHashMap<>();
        double percentSum = 0;
        for (String member : group.members) {
            double p = readDouble("  " + member + "'s percent (0 to skip): ");
            if (p > 0) {
                percents.put(member, p);
                percentSum += p;
            }
        }
        if (Math.abs(percentSum - 100.0) > 0.001) {
            System.out.println("Percentages sum to " + percentSum + "% instead of 100%. Operation cancelled.");
            return null;
        }
        Map<String, Long> shares = new LinkedHashMap<>();
        long assignedTotal = 0;
        String lastMember = null;
        for (Map.Entry<String, Double> entry : percents.entrySet()) {
            long shareAmt = Math.round(total * entry.getValue() / 100.0);
            shares.put(entry.getKey(), shareAmt);
            assignedTotal += shareAmt;
            lastMember = entry.getKey();
        }
        // Balance any rounding delta into the last member's share
        if (lastMember != null) {
            shares.merge(lastMember, total - assignedTotal, Long::sum);
        }
        return shares;
    }

    // =========================================================================
    // FEATURE 9: CLI PROMPT PICKERS & PARSERS
    // =========================================================================

    static Group pickGroup() {
        if (groups.isEmpty()) {
            System.out.println("No groups available. Please create one first.");
            return null;
        }
        List<Group> list = new ArrayList<>(groups.values());
        System.out.println("Select a group:");
        for (int i = 0; i < list.size(); i++) {
            System.out.println("  " + (i + 1) + ". " + list.get(i).name
                    + " (" + String.join(", ", list.get(i).members) + ")");
        }
        int index = readInt("Choose: ");
        if (index < 1 || index > list.size()) {
            System.out.println("Invalid group selection.");
            return null;
        }
        return list.get(index - 1);
    }

    static String pickMember(Group group, String prompt) {
        while (true) {
            System.out.println(prompt);
            for (int i = 0; i < group.members.size(); i++) {
                System.out.println("  " + (i + 1) + ". " + group.members.get(i));
            }
            int index = readInt("Choose: ");
            if (index >= 1 && index <= group.members.size()) {
                return group.members.get(index - 1);
            }
            System.out.println("Invalid selection, please try again.");
        }
    }

    static List<String> pickParticipants(Group group) {
        System.out.println("Split between whom?");
        for (int i = 0; i < group.members.size(); i++) {
            System.out.println("  " + (i + 1) + ". " + group.members.get(i));
        }
        String line = readLine("Numbers separated by commas, or 'all': ");
        List<String> selected = new ArrayList<>();
        if (line.equalsIgnoreCase("all")) {
            selected.addAll(group.members);
            return selected;
        }
        for (String token : line.split(",")) {
            try {
                int index = Integer.parseInt(token.trim());
                String member = group.members.get(index - 1);
                if (!selected.contains(member)) {
                    selected.add(member);
                }
            } catch (Exception ex) {
                System.out.println("Invalid selection: '" + token.trim() + "'");
                return new ArrayList<>();
            }
        }
        return selected;
    }

    // =========================================================================
    // FEATURE 10: UTILITY & FORMATTING HELPERS
    // =========================================================================

    /**
     * Formats paise/cents amount into INR currency string with commas and decimals.
     * e.g., 175000 paise -> ₹1,750.00
     */
    public static String formatCurrency(long paise) {
        return String.format("₹%,.2f", paise / 100.0);
    }

    /**
     * Legacy alias maintained for backwards compatibility with GUI modules.
     */
    public static String money(long paise) {
        return formatCurrency(paise);
    }

    static String readLine(String prompt) {
        System.out.print(prompt);
        return SCANNER.hasNextLine() ? SCANNER.nextLine().trim() : "";
    }

    static int readInt(String prompt) {
        while (true) {
            String s = readLine(prompt);
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException e) {
                System.out.println("Please enter a valid whole number.");
                if (!SCANNER.hasNextLine()) return 0;
            }
        }
    }

    static double readDouble(String prompt) {
        while (true) {
            String s = readLine(prompt);
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException e) {
                System.out.println("Please enter a valid numeric value.");
                if (!SCANNER.hasNextLine()) return 0;
            }
        }
    }

    static long readMoney(String prompt) {
        return Math.round(readDouble(prompt) * 100);
    }
}
