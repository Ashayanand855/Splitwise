import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.*;
import java.util.List;

/**
 * Splitwise India - Native Java Desktop GUI (Swing)
 * Provides a non-web, pure Java desktop GUI for SplitwiseApp.
 */
public class SplitwiseDesktopGUI extends JFrame {

    private final Color PRIMARY = new Color(0, 107, 86);
    private final Color PRIMARY_CONTAINER = new Color(28, 194, 159);
    private final Color BG_SURFACE = new Color(250, 248, 255);
    private final Color CARD_BG = Color.WHITE;
    private final Color TEXT_DARK = new Color(19, 27, 46);
    private final Color TEXT_MUTED = new Color(108, 122, 116);
    private final Color SECONDARY_RED = new Color(181, 35, 48);

    private SplitwiseApp.Group currentGroup;
    private JPanel membersPanel;
    private JPanel expensesFeedPanel;
    private JLabel overallBalanceLabel;
    private JLabel groupSpendLabel;

    // Add Expense Fields
    private JTextField descField;
    private JTextField amountField;
    private JComboBox<String> payerCombo;
    private JComboBox<String> splitTypeCombo;
    private JTextField notesField;

    public SplitwiseDesktopGUI() {
        super("Splitwise India - Desktop GUI");
        SplitwiseApp.initSeedData();
        this.currentGroup = SplitwiseApp.groups.values().iterator().next();

        setSize(460, 840);
        setMinimumSize(new Dimension(420, 700));
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        getContentPane().setBackground(BG_SURFACE);

        initUI();
    }

    public static void launchGUI() {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {}
            new SplitwiseDesktopGUI().setVisible(true);
        });
    }

    private void initUI() {
        setLayout(new BorderLayout());

        // Header Bar
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(BG_SURFACE);
        header.setBorder(new EmptyBorder(12, 16, 12, 16));

        JLabel title = new JLabel("Splitwise India", JLabel.LEFT);
        title.setFont(new Font("SansSerif", Font.BOLD, 18));
        title.setForeground(TEXT_DARK);
        header.add(title, BorderLayout.WEST);

        JLabel groupBadge = new JLabel(currentGroup.name + "  ");
        groupBadge.setFont(new Font("SansSerif", Font.PLAIN, 14));
        groupBadge.setForeground(PRIMARY);
        header.add(groupBadge, BorderLayout.EAST);

        add(header, BorderLayout.NORTH);

        // Tabbed Pane for Navigation
        JTabbedPane tabs = new JTabbedPane();
        tabs.setFont(new Font("SansSerif", Font.BOLD, 12));
        tabs.addTab("Group Detail", createGroupDetailPanel());
        tabs.addTab("+ Add Expense", createAddExpensePanel());
        tabs.addTab("Balances & Debts", createBalancesPanel());

        add(tabs, BorderLayout.CENTER);

        refreshData();
    }

    // ------------------------------------------------------------- Group Detail Tab
    private JComponent createGroupDetailPanel() {
        JPanel main = new JPanel();
        main.setLayout(new BoxLayout(main, BoxLayout.Y_AXIS));
        main.setBackground(BG_SURFACE);
        main.setBorder(new EmptyBorder(12, 16, 16, 16));

        // Hero Card
        JPanel hero = new JPanel(new BorderLayout());
        hero.setBackground(CARD_BG);
        hero.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(220, 225, 240), 1),
                new EmptyBorder(16, 16, 16, 16)
        ));

        JLabel grpTitle = new JLabel("🏖️ " + currentGroup.name);
        grpTitle.setFont(new Font("SansSerif", Font.BOLD, 18));
        grpTitle.setForeground(TEXT_DARK);

        JLabel grpSub = new JLabel("Members: " + String.join(", ", currentGroup.members));
        grpSub.setFont(new Font("SansSerif", Font.PLAIN, 12));
        grpSub.setForeground(TEXT_MUTED);

        overallBalanceLabel = new JLabel("You are all settled up (₹0.00)");
        overallBalanceLabel.setFont(new Font("SansSerif", Font.BOLD, 16));
        overallBalanceLabel.setForeground(PRIMARY);
        overallBalanceLabel.setBorder(new EmptyBorder(10, 0, 10, 0));

        JPanel heroTop = new JPanel(new GridLayout(2, 1));
        heroTop.setBackground(CARD_BG);
        heroTop.add(grpTitle);
        heroTop.add(grpSub);

        JPanel actions = new JPanel(new GridLayout(1, 3, 6, 0));
        actions.setBackground(CARD_BG);

        JButton settleBtn = new JButton("Settle up");
        settleBtn.setBackground(PRIMARY_CONTAINER);
        settleBtn.setForeground(Color.WHITE);
        settleBtn.setFocusPainted(false);
        settleBtn.addActionListener(e -> showSettleDialog());

        JButton refreshBtn = new JButton("Refresh");
        refreshBtn.setBackground(new Color(235, 240, 255));
        refreshBtn.addActionListener(e -> refreshData());

        JButton resetBtn = new JButton("Reset (₹0)");
        resetBtn.setBackground(new Color(255, 235, 235));
        resetBtn.setForeground(SECONDARY_RED);
        resetBtn.addActionListener(e -> {
            int confirm = JOptionPane.showConfirmDialog(this, "Reset all expenses to ₹0.00?", "Confirm Reset", JOptionPane.YES_NO_OPTION);
            if (confirm == JOptionPane.YES_OPTION) {
                synchronized (currentGroup) {
                    currentGroup.expenses.clear();
                }
                refreshData();
            }
        });

        actions.add(settleBtn);
        actions.add(refreshBtn);
        actions.add(resetBtn);

        hero.add(heroTop, BorderLayout.NORTH);
        hero.add(overallBalanceLabel, BorderLayout.CENTER);
        hero.add(actions, BorderLayout.SOUTH);

        main.add(hero);
        main.add(Box.createVerticalStrut(14));

        // Balances Carousel / Row
        JLabel sec1 = new JLabel("INDIVIDUAL BALANCES (ALL SETTLED AT ₹0.00)");
        sec1.setFont(new Font("SansSerif", Font.BOLD, 11));
        sec1.setForeground(TEXT_MUTED);
        sec1.setAlignmentX(Component.LEFT_ALIGNMENT);
        main.add(sec1);
        main.add(Box.createVerticalStrut(6));

        membersPanel = new JPanel();
        membersPanel.setLayout(new BoxLayout(membersPanel, BoxLayout.Y_AXIS));
        membersPanel.setBackground(BG_SURFACE);
        main.add(membersPanel);
        main.add(Box.createVerticalStrut(14));

        // Expenses Timeline
        JLabel sec2 = new JLabel("RECENT TRANSACTIONS (0 RECORDED)");
        sec2.setFont(new Font("SansSerif", Font.BOLD, 11));
        sec2.setForeground(TEXT_MUTED);
        sec2.setAlignmentX(Component.LEFT_ALIGNMENT);
        main.add(sec2);
        main.add(Box.createVerticalStrut(6));

        expensesFeedPanel = new JPanel();
        expensesFeedPanel.setLayout(new BoxLayout(expensesFeedPanel, BoxLayout.Y_AXIS));
        expensesFeedPanel.setBackground(CARD_BG);
        expensesFeedPanel.setBorder(BorderFactory.createLineBorder(new Color(220, 225, 240), 1));
        main.add(expensesFeedPanel);
        main.add(Box.createVerticalStrut(14));

        // Group Spend Footer
        groupSpendLabel = new JLabel("Total group spend: ₹0.00");
        groupSpendLabel.setFont(new Font("SansSerif", Font.BOLD, 13));
        groupSpendLabel.setForeground(TEXT_DARK);
        main.add(groupSpendLabel);

        JScrollPane scroll = new JScrollPane(main);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    // ------------------------------------------------------------- Add Expense Tab
    private JComponent createAddExpensePanel() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBackground(CARD_BG);
        p.setBorder(new EmptyBorder(20, 20, 20, 20));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(6, 6, 6, 6);
        gbc.weightx = 1.0;

        int row = 0;

        JLabel lblTitle = new JLabel("Add New Expense");
        lblTitle.setFont(new Font("SansSerif", Font.BOLD, 18));
        lblTitle.setForeground(PRIMARY);
        gbc.gridx = 0; gbc.gridy = row++; gbc.gridwidth = 2;
        p.add(lblTitle, gbc);

        gbc.gridwidth = 1;

        // Description
        gbc.gridx = 0; gbc.gridy = row;
        p.add(new JLabel("Description:"), gbc);
        descField = new JTextField("Dinner at Fisherman's Wharf");
        gbc.gridx = 1; gbc.gridy = row++;
        p.add(descField, gbc);

        // Amount
        gbc.gridx = 0; gbc.gridy = row;
        p.add(new JLabel("Amount (₹):"), gbc);
        amountField = new JTextField("3600.00");
        gbc.gridx = 1; gbc.gridy = row++;
        p.add(amountField, gbc);

        // Paid By
        gbc.gridx = 0; gbc.gridy = row;
        p.add(new JLabel("Paid by:"), gbc);
        payerCombo = new JComboBox<>(currentGroup.members.toArray(new String[0]));
        payerCombo.setSelectedItem("You");
        gbc.gridx = 1; gbc.gridy = row++;
        p.add(payerCombo, gbc);

        // Split Mode
        gbc.gridx = 0; gbc.gridy = row;
        p.add(new JLabel("Split Mode:"), gbc);
        splitTypeCombo = new JComboBox<>(new String[]{"Equally (=)", "Exact Amounts", "Percentages (%)"});
        gbc.gridx = 1; gbc.gridy = row++;
        p.add(splitTypeCombo, gbc);

        // Notes
        gbc.gridx = 0; gbc.gridy = row;
        p.add(new JLabel("UPI / Notes:"), gbc);
        notesField = new JTextField("UPI Ref: 4291889012");
        gbc.gridx = 1; gbc.gridy = row++;
        p.add(notesField, gbc);

        // Save Button
        JButton saveBtn = new JButton("Save Expense");
        saveBtn.setFont(new Font("SansSerif", Font.BOLD, 14));
        saveBtn.setBackground(PRIMARY);
        saveBtn.setForeground(Color.WHITE);
        saveBtn.setPreferredSize(new Dimension(200, 42));
        saveBtn.addActionListener(e -> handleAddExpense());

        gbc.gridx = 0; gbc.gridy = row++; gbc.gridwidth = 2;
        gbc.insets = new Insets(18, 6, 6, 6);
        p.add(saveBtn, gbc);

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBackground(BG_SURFACE);
        wrapper.add(p, BorderLayout.NORTH);
        return new JScrollPane(wrapper);
    }

    // ------------------------------------------------------------- Balances Tab
    private JComponent createBalancesPanel() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(BG_SURFACE);
        p.setBorder(new EmptyBorder(16, 16, 16, 16));

        JLabel title = new JLabel("Simplified Debt Settlement");
        title.setFont(new Font("SansSerif", Font.BOLD, 16));
        title.setForeground(PRIMARY);
        p.add(title);
        p.add(Box.createVerticalStrut(8));

        JTextArea debtsArea = new JTextArea();
        debtsArea.setEditable(false);
        debtsArea.setFont(new Font("Monospaced", Font.PLAIN, 13));
        debtsArea.setBackground(CARD_BG);
        debtsArea.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(220, 225, 240), 1),
                new EmptyBorder(10, 10, 10, 10)
        ));

        List<String> debts = currentGroup.simplifiedDebts();
        if (debts.isEmpty()) {
            debtsArea.setText("Everyone in this group is settled up!");
        } else {
            StringBuilder sb = new StringBuilder();
            for (String d : debts) {
                sb.append("• ").append(d).append("\n\n");
            }
            debtsArea.setText(sb.toString());
        }

        p.add(debtsArea);
        return new JScrollPane(p);
    }

    // ------------------------------------------------------------- Refresh Logic
    public void refreshData() {
        Map<String, Long> net = currentGroup.netBalances();
        long youNet = net.getOrDefault("You", 0L);

        if (youNet > 0) {
            overallBalanceLabel.setText("You are owed " + SplitwiseApp.money(youNet));
            overallBalanceLabel.setForeground(PRIMARY);
        } else if (youNet < 0) {
            overallBalanceLabel.setText("You owe " + SplitwiseApp.money(-youNet));
            overallBalanceLabel.setForeground(SECONDARY_RED);
        } else {
            overallBalanceLabel.setText("You are all settled up");
            overallBalanceLabel.setForeground(TEXT_MUTED);
        }

        // Render member cards
        membersPanel.removeAll();
        for (String m : currentGroup.members) {
            if ("You".equals(m)) continue;
            long val = net.getOrDefault(m, 0L);

            JPanel card = new JPanel(new BorderLayout());
            card.setBackground(CARD_BG);
            card.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new Color(230, 235, 245), 1),
                    new EmptyBorder(8, 12, 8, 12)
            ));

            JLabel nameLbl = new JLabel(m);
            nameLbl.setFont(new Font("SansSerif", Font.BOLD, 13));

            JLabel statusLbl = new JLabel();
            statusLbl.setFont(new Font("SansSerif", Font.BOLD, 12));
            if (val < 0) {
                statusLbl.setText("owes you " + SplitwiseApp.money(-val));
                statusLbl.setForeground(PRIMARY);
            } else if (val > 0) {
                statusLbl.setText("you owe " + SplitwiseApp.money(val));
                statusLbl.setForeground(SECONDARY_RED);
            } else {
                statusLbl.setText("settled up (₹0.00)");
                statusLbl.setForeground(TEXT_MUTED);
            }

            card.add(nameLbl, BorderLayout.WEST);
            card.add(statusLbl, BorderLayout.EAST);
            membersPanel.add(card);
            membersPanel.add(Box.createVerticalStrut(4));
        }

        // Render expenses timeline
        expensesFeedPanel.removeAll();
        if (currentGroup.expenses.isEmpty()) {
            JPanel emptyRow = new JPanel(new BorderLayout());
            emptyRow.setBackground(CARD_BG);
            emptyRow.setBorder(new EmptyBorder(36, 20, 36, 20));
            JLabel emptyLbl = new JLabel("<html><center><font size='4'><b>No expenses recorded yet</b></font><br><br><font color='#6c7a74'>All group balances are settled at ₹0.00.<br>Switch to the <b>+ Add Expense</b> tab to record your first bill.</font></center></html>", JLabel.CENTER);
            emptyRow.add(emptyLbl, BorderLayout.CENTER);
            expensesFeedPanel.add(emptyRow);
        } else {
            for (SplitwiseApp.Expense e : currentGroup.expenses) {
                JPanel row = new JPanel(new BorderLayout());
                row.setBackground(CARD_BG);
                row.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(240, 242, 250)),
                        new EmptyBorder(10, 12, 10, 12)
                ));

                JLabel desc = new JLabel(e.description);
                desc.setFont(new Font("SansSerif", Font.BOLD, 13));

                JLabel sub = new JLabel((e.settlement ? "Settlement • " : e.payer + " paid • ") + e.date);
                sub.setFont(new Font("SansSerif", Font.PLAIN, 11));
                sub.setForeground(TEXT_MUTED);

                JPanel left = new JPanel(new GridLayout(2, 1));
                left.setBackground(CARD_BG);
                left.add(desc);
                left.add(sub);

                JLabel amt = new JLabel(SplitwiseApp.money(e.total));
                amt.setFont(new Font("SansSerif", Font.BOLD, 13));
                amt.setForeground(e.settlement ? TEXT_MUTED : PRIMARY);

                row.add(left, BorderLayout.CENTER);
                row.add(amt, BorderLayout.EAST);
                expensesFeedPanel.add(row);
            }
        }

        long totalSpend = 0;
        for (SplitwiseApp.Expense e : currentGroup.expenses) {
            if (!e.settlement) totalSpend += e.total;
        }
        groupSpendLabel.setText("Total group spend: " + SplitwiseApp.money(totalSpend));

        membersPanel.revalidate();
        membersPanel.repaint();
        expensesFeedPanel.revalidate();
        expensesFeedPanel.repaint();
    }

    private void handleAddExpense() {
        String desc = descField.getText().trim();
        String amtStr = amountField.getText().trim();
        String payer = (String) payerCombo.getSelectedItem();
        String notes = notesField.getText().trim();

        if (desc.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please enter a description.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        double val;
        try {
            val = Double.parseDouble(amtStr.replace(",", ""));
            if (val <= 0) throw new NumberFormatException();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Please enter a valid amount.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        long totalPaise = Math.round(val * 100);
        int num = currentGroup.members.size();
        long base = totalPaise / num;
        long rem = totalPaise % num;

        Map<String, Long> shares = new LinkedHashMap<>();
        for (int i = 0; i < num; i++) {
            shares.put(currentGroup.members.get(i), base + (i < rem ? 1 : 0));
        }

        SplitwiseApp.Expense exp = new SplitwiseApp.Expense(
                "exp_" + System.currentTimeMillis(),
                desc, payer, shares, false, "restaurant", "Today", notes
        );

        synchronized (currentGroup) {
            currentGroup.expenses.add(0, exp);
        }

        JOptionPane.showMessageDialog(this, "Expense added successfully!", "Saved", JOptionPane.INFORMATION_MESSAGE);
        refreshData();
    }

    private void showSettleDialog() {
        JComboBox<String> fromBox = new JComboBox<>(currentGroup.members.toArray(new String[0]));
        JComboBox<String> toBox = new JComboBox<>(currentGroup.members.toArray(new String[0]));
        fromBox.setSelectedItem("Rahul");
        toBox.setSelectedItem("You");
        JTextField amtInput = new JTextField("1800.00");

        JPanel form = new JPanel(new GridLayout(3, 2, 8, 8));
        form.add(new JLabel("Who paid:"));
        form.add(fromBox);
        form.add(new JLabel("To whom:"));
        form.add(toBox);
        form.add(new JLabel("Amount (₹):"));
        form.add(amtInput);

        int res = JOptionPane.showConfirmDialog(this, form, "Record Settle Up Payment", JOptionPane.OK_CANCEL_OPTION);
        if (res == JOptionPane.OK_OPTION) {
            String from = (String) fromBox.getSelectedItem();
            String to = (String) toBox.getSelectedItem();
            if (from.equals(to)) {
                JOptionPane.showMessageDialog(this, "Payer and receiver must be different.");
                return;
            }
            try {
                double amt = Double.parseDouble(amtInput.getText().trim());
                long paise = Math.round(amt * 100);
                Map<String, Long> shares = new LinkedHashMap<>();
                shares.put(to, paise);

                SplitwiseApp.Expense sExp = new SplitwiseApp.Expense(
                        "settle_" + System.currentTimeMillis(),
                        from + " paid " + to + " " + SplitwiseApp.money(paise),
                        from, shares, true, "price_check", "Today", "GPay UPI Payment recorded"
                );

                synchronized (currentGroup) {
                    currentGroup.expenses.add(0, sExp);
                }
                refreshData();
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Invalid amount.");
            }
        }
    }

    public static void main(String[] args) {
        launchGUI();
    }
}
