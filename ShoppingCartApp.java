import java.math.BigDecimal;
import java.sql.*;
import java.util.Scanner;

public class ShoppingCartApp {

    // jdbc:mysql: - defines the API and driver to use
    // localhost:3306 - mysql server running at port 3306
    // shoppingCartDb - name of the database
    private static final String URL  = "jdbc:mysql://localhost:3306/shoppingCartDb";
    private static final String USER = "root";
    private static final String PASS = "hemy27";

    // connection object
    private static Connection conn;
    private static final Scanner sc = new Scanner(System.in);

    public static void main(String[] args) throws Exception {
        // DriverManager - class that manages jdbc drivers
        // getConnection - method that establishes a connection to the database
        conn = DriverManager.getConnection(URL, USER, PASS);
        // setAutoCommit(false) - disables auto-commit mode, meaning transactions will not be committed automatically.
        // Instead, transactions will be committed manually using conn.commit() or rolled back using conn.rollback()
        conn.setAutoCommit(false);
        System.out.println("=== SHOPPING CART APP ===");

        int loggedInUserId = promptUserId(); // line 287
        if (loggedInUserId < 0) {
            System.out.println("Login failed. Exiting...");
            return;
        }

        boolean running = true;
        while (running) {
            System.out.println("\nMAIN MENU (User ID: " + loggedInUserId + ")");
            System.out.println("1. Add item to cart");
            System.out.println("2. Checkout");
            System.out.println("0. Exit");
            System.out.print("Choice: ");
            String choice = sc.nextLine().trim();

            switch (choice) {
                case "1" -> addItemToCart(loggedInUserId); // line 53
                case "2" -> checkout(loggedInUserId); // line 128
                case "0" -> running = false;
                default  -> System.out.println("Invalid option.");
            }
        }
        conn.close();
        System.out.println("Goodbye!");
    }

    private static void addItemToCart(int userId) throws SQLException {
        listProducts(); // line 301
        System.out.print("Enter product ID to add: ");
        int productId = parseIntOrNeg(sc.nextLine()); // line 372
        if (productId < 0) { System.out.println("Invalid product ID."); return; }

        System.out.print("Enter quantity: ");
        int qty = parseIntOrNeg(sc.nextLine());
        if (qty <= 0) { System.out.println("Quantity must be > 0."); return; }

        try {
            // Fetch product
            String productName; // variables to store product details
            BigDecimal price;
            int currentStock;
            // PreparedStatement - class that represents a precompiled SQL statement - has placeholders ? - for security and efficiency
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT name, price, stockQty FROM product WHERE productId = ?")) { // SQL query on product table
                ps.setInt(1, productId);
                ResultSet rs = ps.executeQuery();
                if (!rs.next()) { System.out.println("Product not found."); return; }
                productName  = rs.getString("name");
                price        = rs.getBigDecimal("price");
                currentStock = rs.getInt("stockQty");
            }

            // Check stock
            if (currentStock < qty) {
                System.out.println("Insufficient stock. Available: " + currentStock);
                return;
            }

            // Get or create cart
            int cartId = getActiveCartId(userId); // to check if the cart already exists
            if (cartId < 0) cartId = createCart(userId); // if not, create a new one

            // Check if item already in cart
            int alreadyInCart = 0;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT quantity FROM cartItem WHERE cartId=? AND productId=?")) { // SQL query on cartItem table
                ps.setInt(1, cartId); ps.setInt(2, productId);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) alreadyInCart = rs.getInt("quantity");
            }

            // Upsert cart item
            if (alreadyInCart > 0) { // update since entry of that product is already in cart
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE cartItem SET quantity = quantity + ? WHERE cartId = ? AND productId = ?")) {
                    ps.setInt(1, qty); ps.setInt(2, cartId); ps.setInt(3, productId);
                    ps.executeUpdate();
                }
            } else { // otherwise we need to insert
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO cartItem (cartId, productId, quantity) VALUES (?,?,?)")) {
                    ps.setInt(1, cartId); ps.setInt(2, productId); ps.setInt(3, qty);
                    ps.executeUpdate();
                }
            }

            // Decrement stock when adding to cart and not at checkout
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE product SET stockQty = stockQty - ? WHERE productId = ?")) {
                ps.setInt(1, qty); ps.setInt(2, productId);
                ps.executeUpdate();
            }

            conn.commit();
            System.out.println("Added " + qty + "x '" + productName + "' to cart.");

        } catch (SQLException e) {
            conn.rollback();
            System.out.println("Transaction rolled back: " + e.getMessage());
        }
    }

    private static void checkout(int userId) throws SQLException {
        int cartId = getActiveCartId(userId); // required
        if (cartId < 0) { System.out.println("No active cart to checkout."); return; }

        printCart(cartId); // prints cart

        // Calculate total
        BigDecimal total;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT SUM(ci.quantity * p.price) AS total FROM cartItem ci " +
                "JOIN product p ON ci.productId = p.productId WHERE ci.cartId = ?")) {
            ps.setInt(1, cartId);
            ResultSet rs = ps.executeQuery();
            rs.next();
            total = rs.getBigDecimal("total");
            if (total == null || total.compareTo(BigDecimal.ZERO) == 0) {
                System.out.println("Cart is empty.");
                return;
            }
        }

        System.out.printf("Order total: Rs.%.2f%n", total);

        // Check wallet
        BigDecimal walletBalance = getWalletBalance(userId);
        System.out.printf("Wallet balance: Rs.%.2f%n", walletBalance);
        if (walletBalance.compareTo(total) < 0) { // walletBalance < total
            System.out.println("Insufficient wallet balance. Flushing cart and restoring stock...");
            flushCartAndRestoreStock(cartId);
            return;
        }

        System.out.print("Confirm checkout? (y/n): ");
        if (!sc.nextLine().trim().equalsIgnoreCase("y")) {
            System.out.println("Checkout cancelled. Flushing cart and restoring stock...");
            flushCartAndRestoreStock(cartId);
            return;
        }

        try {
            // Deduct wallet
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE wallet SET balance = balance - ? WHERE userId = ?")) {
                ps.setBigDecimal(1, total); ps.setInt(2, userId);
                ps.executeUpdate();
            }

            // Close cart
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE cart SET status = 'CHECKED_OUT' WHERE cartId = ?")) {
                ps.setInt(1, cartId);
                ps.executeUpdate();
            }

            // Create bill
            int billId;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO bill (cartId, userId, totalAmount) VALUES (?,?,?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setInt(1, cartId); ps.setInt(2, userId); ps.setBigDecimal(3, total);
                ps.executeUpdate();
                ResultSet gk = ps.getGeneratedKeys();
                gk.next();
                billId = gk.getInt(1); // store billId
            }

            // Insert bill items
            try (PreparedStatement fetchPs = conn.prepareStatement(
                    "SELECT ci.productId, p.name, p.price, ci.quantity FROM cartItem ci " +
                    "JOIN product p ON ci.productId = p.productId WHERE ci.cartId = ?");
                 PreparedStatement insertPs = conn.prepareStatement(
                    "INSERT INTO billItem (billId, productId, productName, quantity, unitPrice) VALUES (?,?,?,?,?)")) {
                fetchPs.setInt(1, cartId);
                ResultSet rs = fetchPs.executeQuery();
                while (rs.next()) {
                    insertPs.setInt(1, billId);
                    insertPs.setInt(2, rs.getInt("productId"));
                    insertPs.setString(3, rs.getString("name"));
                    insertPs.setInt(4, rs.getInt("quantity"));
                    insertPs.setBigDecimal(5, rs.getBigDecimal("price"));
                    insertPs.addBatch();
                }
                insertPs.executeBatch();
            }

            conn.commit();
            printBill(billId);
            System.out.printf("Checkout successful! Remaining balance: Rs.%.2f%n", getWalletBalance(userId));

        } catch (SQLException e) {
            conn.rollback();
            System.out.println("Checkout failed, rolled back: " + e.getMessage());
        }
    }

    // helper functions

    private static void flushCartAndRestoreStock(int cartId) {
        try {
            // Restore stock
            try (PreparedStatement fetchPs = conn.prepareStatement( // fetch old quantity of product in cart
                    "SELECT productId, quantity FROM cartItem WHERE cartId = ?");
                 PreparedStatement updatePs = conn.prepareStatement( // now increment
                    "UPDATE product SET stockQty = stockQty + ? WHERE productId = ?")) {
                
                fetchPs.setInt(1, cartId);
                ResultSet rs = fetchPs.executeQuery();
                // read from the fetched entries and for each fetched entry update the stock
                while (rs.next()) {
                    updatePs.setInt(1, rs.getInt("quantity"));
                    updatePs.setInt(2, rs.getInt("productId"));
                    updatePs.addBatch(); // accumulates updates for the batch
                }
                updatePs.executeBatch(); // executes the whole batch
            }

            // Clear cart items
            try (PreparedStatement deletePs = conn.prepareStatement(
                    "DELETE FROM cartItem WHERE cartId = ?")) {
                deletePs.setInt(1, cartId);
                deletePs.executeUpdate();
            }

            conn.commit();
            System.out.println("Cart flushed successfully.");
        } catch (SQLException e) {
            try { conn.rollback(); } catch (SQLException ex) {}
            System.out.println("Failed to flush cart: " + e.getMessage());
        }
    }

    // cart already exists and you need cartId
    private static int getActiveCartId(int userId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT cartId FROM cart WHERE userId = ? AND status = 'ACTIVE' LIMIT 1")) {
            ps.setInt(1, userId);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getInt("cartId") : -1;
        }
    }

    private static int createCart(int userId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO cart (userId) VALUES (?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, userId);
            ps.executeUpdate();
            ResultSet gk = ps.getGeneratedKeys();
            gk.next();
            return gk.getInt(1);
        }
    }

    private static BigDecimal getWalletBalance(int userId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT balance FROM wallet WHERE userId = ?")) {
            ps.setInt(1, userId);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getBigDecimal("balance") : BigDecimal.ZERO;
        }
    }

    // validator if the user id exists in database or not
    private static int promptUserId() throws SQLException {
        System.out.print("Enter user ID: ");
        int userId = parseIntOrNeg(sc.nextLine());
        if (userId < 0) { System.out.println("Invalid user ID."); return -1; }
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT userId FROM users WHERE userId = ?")) {
            ps.setInt(1, userId);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) { System.out.println("User not found."); return -1; }
        }
        return userId;
    }

    // prints the list of products from database
    private static void listProducts() throws SQLException {
        System.out.println("\nProducts:");
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                "SELECT productId, name, price, stockQty FROM product ORDER BY productId")) {
            while (rs.next()) {
                System.out.printf("  [%d] %s - Rs.%.2f (Stock: %d)%n",
                    rs.getInt("productId"),
                    rs.getString("name"),
                    rs.getBigDecimal("price"),
                    rs.getInt("stockQty"));
            }
        }
    }

    private static void printCart(int cartId) throws SQLException {
        System.out.println("\nCart #" + cartId + ":");
        BigDecimal total = BigDecimal.ZERO;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT p.name, p.price, ci.quantity, (p.price * ci.quantity) AS subtotal " +
                "FROM cartItem ci JOIN product p ON ci.productId = p.productId WHERE ci.cartId = ?")) {
            ps.setInt(1, cartId);
            ResultSet rs = ps.executeQuery();
            boolean empty = true;
            while (rs.next()) {
                empty = false;
                BigDecimal subtotal = rs.getBigDecimal("subtotal");
                total = total.add(subtotal);
                System.out.printf("  %s x%d = Rs.%.2f%n",
                    rs.getString("name"),
                    rs.getInt("quantity"),
                    subtotal);
            }
            if (empty) System.out.println("  (cart is empty)");
        }
        System.out.printf("  Total: Rs.%.2f%n", total);
    }

    private static void printBill(int billId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT b.billId, b.billedAt, b.totalAmount, u.name " +
                "FROM bill b JOIN users u ON b.userId = u.userId WHERE b.billId = ?")) {
            ps.setInt(1, billId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                System.out.println("\n=== BILL #" + billId + " ===");
                System.out.println("Customer : " + rs.getString("name"));
                System.out.println("Date     : " + rs.getTimestamp("billedAt"));
            }
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT productName, quantity, unitPrice, lineTotal FROM billItem WHERE billId = ?")) {
            ps.setInt(1, billId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                System.out.printf("  %s x%d = Rs.%.2f%n",
                    rs.getString("productName"),
                    rs.getInt("quantity"),
                    rs.getBigDecimal("lineTotal"));
            }
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT totalAmount FROM bill WHERE billId = ?")) {
            ps.setInt(1, billId);
            ResultSet rs = ps.executeQuery();
            rs.next();
            System.out.printf("Total: Rs.%.2f%n", rs.getBigDecimal("totalAmount"));
        }
    }

    // crashes if only .parseInt is used so error handling
    private static int parseIntOrNeg(String s) {
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return -1; }
    }
}
