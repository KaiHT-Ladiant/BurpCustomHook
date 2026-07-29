package com.webhookpage.ui;

import burp.api.montoya.MontoyaApi;
import com.webhookpage.PublicUrlService;
import com.webhookpage.WebhookConfig;
import com.webhookpage.WebhookRequestLog;
import com.webhookpage.WebhookUrlBuilder;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Main suite tab UI for Webhook Page extension.
 */
public final class WebhookTab extends JPanel {

    private static final String WEBHOOK_OFF_PLACEHOLDER = "(Webhook OFF)";
    private static final String GENERATING_PLACEHOLDER = "Generating...";

    private final MontoyaApi api;
    private final WebhookConfig config;
    private final WebhookUrlBuilder urlBuilder;
    private final PublicUrlService publicUrlService;
    private final RequestLogTableModel logModel;
    private final AtomicInteger discoveryGeneration = new AtomicInteger();

    private final JCheckBox publicWebhookToggle = new JCheckBox("Public Webhook (available ON/OFF)");
    private final JTextField webhookUrlField = new JTextField();
    private final JTextField extensionPortField = new JTextField();
    private final JTextField publicStatusField = new JTextField();
    private final JTextField requiredStringField = new JTextField();
    private final JTextField pathField = new JTextField();
    private final JTextArea htmlArea = new JTextArea();
    private final JLabel statusLabel = new JLabel();
    private final JLabel listenerHintLabel = new JLabel();

    private boolean suppressLiveSync;
    private volatile PublicUrlService.Source lastSource = PublicUrlService.Source.NONE;

    public WebhookTab(
            MontoyaApi api,
            WebhookConfig config,
            WebhookUrlBuilder urlBuilder,
            PublicUrlService publicUrlService
    ) {
        this.api = api;
        this.config = config;
        this.urlBuilder = urlBuilder;
        this.publicUrlService = publicUrlService;
        this.logModel = new RequestLogTableModel(500);

        setLayout(new BorderLayout(8, 8));
        setBorder(new EmptyBorder(10, 10, 10, 10));

        add(buildTopPanel(), BorderLayout.NORTH);
        add(buildCenterPanel(), BorderLayout.CENTER);
        add(buildLogPanel(), BorderLayout.SOUTH);

        wireLiveSync();
        loadFromConfig();
        refreshWebhookUrl();

        config.addChangeListener(c -> SwingUtilities.invokeLater(() -> {
            if (!suppressLiveSync) {
                refreshWebhookUrl();
                updateStatusLabel();
            }
        }));
    }

    public void addLogEntry(WebhookRequestLog entry) {
        SwingUtilities.invokeLater(() -> logModel.addEntry(entry));
    }

    private JPanel buildTopPanel() {
        JPanel root = new JPanel();
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));

        JPanel urlPanel = new JPanel(new BorderLayout(8, 8));
        urlPanel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(),
                "Webhook URL",
                TitledBorder.LEFT,
                TitledBorder.TOP
        ));

        publicWebhookToggle.setFont(publicWebhookToggle.getFont().deriveFont(Font.BOLD, 13f));
        publicWebhookToggle.setSelected(false);
        publicWebhookToggle.setToolTipText(
                "When ON, the extension auto-generates a ready-to-use public URL "
                        + "(cloudflared → ngrok → LAN) and serves webhook responses. "
                        + "When OFF, matching requests return 503."
        );
        publicWebhookToggle.addActionListener(e -> onPublicWebhookToggled());

        JLabel title = new JLabel("Deployable Webhook URL (auto public base + required token path)");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 13f));

        webhookUrlField.setEditable(false);
        webhookUrlField.setFont(new Font(Font.MONOSPACED, Font.BOLD, 16));
        webhookUrlField.setBackground(UIManager.getColor("TextField.background"));
        webhookUrlField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(76, 175, 80), 2),
                new EmptyBorder(8, 10, 8, 10)
        ));

        JButton copyBtn = new JButton("Copy URL");
        copyBtn.addActionListener(e -> copyUrl());

        JButton refreshBtn = new JButton("Refresh URL");
        refreshBtn.addActionListener(e -> {
            if (publicWebhookToggle.isSelected()) {
                startPublicUrlDiscovery();
            } else {
                refreshWebhookUrl();
            }
        });

        JPanel urlButtons = new JPanel();
        urlButtons.setLayout(new BoxLayout(urlButtons, BoxLayout.Y_AXIS));
        urlButtons.add(copyBtn);
        urlButtons.add(Box.createVerticalStrut(6));
        urlButtons.add(refreshBtn);

        JPanel urlFieldWrap = new JPanel(new BorderLayout(0, 4));
        urlFieldWrap.add(publicWebhookToggle, BorderLayout.NORTH);
        JPanel titleAndUrl = new JPanel(new BorderLayout(0, 4));
        titleAndUrl.add(title, BorderLayout.NORTH);
        titleAndUrl.add(webhookUrlField, BorderLayout.CENTER);
        urlFieldWrap.add(titleAndUrl, BorderLayout.CENTER);
        listenerHintLabel.setFont(listenerHintLabel.getFont().deriveFont(Font.PLAIN, 11f));
        listenerHintLabel.setForeground(new Color(180, 80, 0));
        urlFieldWrap.add(listenerHintLabel, BorderLayout.SOUTH);

        urlPanel.add(urlFieldWrap, BorderLayout.CENTER);
        urlPanel.add(urlButtons, BorderLayout.EAST);

        JPanel metaPanel = new JPanel(new GridBagLayout());
        metaPanel.setBorder(new EmptyBorder(6, 4, 0, 4));
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(2, 4, 2, 4);
        gc.anchor = GridBagConstraints.WEST;
        gc.fill = GridBagConstraints.HORIZONTAL;

        gc.gridx = 0;
        gc.gridy = 0;
        gc.weightx = 0;
        metaPanel.add(new JLabel("Local listen (127.0.0.1):"), gc);
        gc.gridx = 1;
        gc.weightx = 1;
        extensionPortField.setEditable(false);
        extensionPortField.setFont(new Font(Font.MONOSPACED, Font.BOLD, 13));
        extensionPortField.setToolTipText(
                "Local Extension HTTP server only. Point cloudflared/ngrok at this port."
        );
        metaPanel.add(extensionPortField, gc);

        gc.gridx = 0;
        gc.gridy = 1;
        gc.weightx = 0;
        metaPanel.add(new JLabel("Public URL status:"), gc);
        gc.gridx = 1;
        gc.weightx = 1;
        publicStatusField.setEditable(false);
        publicStatusField.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        publicStatusField.setToolTipText(
                "Auto-generated: cloudflared Quick Tunnel, else ngrok API, else LAN IP."
        );
        metaPanel.add(publicStatusField, gc);

        root.add(urlPanel);
        root.add(Box.createVerticalStrut(6));
        root.add(metaPanel);
        return root;
    }

    private JPanel buildCenterPanel() {
        JPanel settings = new JPanel(new BorderLayout(8, 8));
        settings.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(),
                "Settings",
                TitledBorder.LEFT,
                TitledBorder.TOP
        ));

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;

        c.gridx = 0;
        c.gridy = 0;
        c.weightx = 0;
        form.add(new JLabel("Required token:"), c);
        c.gridx = 1;
        c.weightx = 1;
        requiredStringField.setToolTipText(
                "Must appear in the deployable (public) Webhook URL path (default: kai_ht)."
        );
        form.add(requiredStringField, c);

        c.gridx = 0;
        c.gridy = 1;
        c.weightx = 0;
        form.add(new JLabel("Webhook path:"), c);
        c.gridx = 1;
        c.weightx = 1;
        pathField.setToolTipText(
                "e.g. /kai_ht/webhook — required token is forced into the public URL path automatically."
        );
        form.add(pathField, c);

        JPanel buttons = new JPanel();
        JButton applyBtn = new JButton("Apply");
        applyBtn.addActionListener(e -> applySettings());
        JButton resetBtn = new JButton("Reset to default page");
        resetBtn.addActionListener(e -> resetDefaults());
        JButton clearLogBtn = new JButton("Clear log");
        clearLogBtn.addActionListener(e -> logModel.clear());
        buttons.add(applyBtn);
        buttons.add(resetBtn);
        buttons.add(clearLogBtn);

        c.gridx = 0;
        c.gridy = 2;
        c.gridwidth = 2;
        c.weightx = 1;
        form.add(buttons, c);

        c.gridy = 3;
        updateStatusLabel();
        form.add(statusLabel, c);

        htmlArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        htmlArea.setLineWrap(false);
        htmlArea.setTabSize(4);
        JScrollPane htmlScroll = new JScrollPane(htmlArea);
        htmlScroll.setBorder(BorderFactory.createTitledBorder("Response HTML"));
        htmlScroll.setPreferredSize(new Dimension(100, 260));

        settings.add(form, BorderLayout.NORTH);
        settings.add(htmlScroll, BorderLayout.CENTER);
        return settings;
    }

    private JPanel buildLogPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(),
                "Request log",
                TitledBorder.LEFT,
                TitledBorder.TOP
        ));

        JTable table = new JTable(logModel);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        table.setRowHeight(22);
        table.getColumnModel().getColumn(0).setPreferredWidth(140);
        table.getColumnModel().getColumn(1).setPreferredWidth(70);
        table.getColumnModel().getColumn(2).setPreferredWidth(280);
        table.getColumnModel().getColumn(3).setPreferredWidth(110);
        table.getColumnModel().getColumn(4).setPreferredWidth(180);
        table.getColumnModel().getColumn(5).setPreferredWidth(260);

        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(100, 180));
        panel.add(scroll, BorderLayout.CENTER);
        return panel;
    }

    private void wireLiveSync() {
        DocumentListener live = new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                onLiveFieldsChanged();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                onLiveFieldsChanged();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                onLiveFieldsChanged();
            }
        };
        requiredStringField.getDocument().addDocumentListener(live);
        pathField.getDocument().addDocumentListener(live);
    }

    private void onPublicWebhookToggled() {
        if (publicWebhookToggle.isSelected()) {
            config.setEnabled(true);
            startPublicUrlDiscovery();
        } else {
            discoveryGeneration.incrementAndGet();
            config.setEnabled(false);
            publicUrlService.clear();
            config.setPublicAddress("");
            lastSource = PublicUrlService.Source.NONE;
            refreshWebhookUrl();
            updateStatusLabel();
            api.logging().logToOutput("[Webhook Page] Public Webhook OFF — matching path returns 503.");
        }
    }

    private void startPublicUrlDiscovery() {
        int generation = discoveryGeneration.incrementAndGet();
        int port = urlBuilder.getExtensionPort();

        webhookUrlField.setText(GENERATING_PLACEHOLDER);
        publicStatusField.setText("Public: ON | Source: discovering... | URL: (pending)");
        listenerHintLabel.setText("Generating public URL (cloudflared → ngrok → LAN)...");
        api.logging().logToOutput("[Webhook Page] Public Webhook ON — discovering public URL...");

        publicUrlService.discoverAsync(port, result -> {
            if (generation != discoveryGeneration.get() || !publicWebhookToggle.isSelected()) {
                return;
            }
            SwingUtilities.invokeLater(() -> {
                if (generation != discoveryGeneration.get() || !publicWebhookToggle.isSelected()) {
                    return;
                }
                lastSource = result.source();
                config.setPublicAddress(result.publicBaseUrl());
                softApplyPathForServing();
                refreshWebhookUrl();
                updateStatusLabel();
                if (result.hint() != null && !result.hint().isBlank()) {
                    listenerHintLabel.setText(result.hint());
                    api.logging().logToOutput("[Webhook Page] " + result.hint());
                }
                api.logging().logToOutput(
                        "[Webhook Page] Public URL ready (" + result.source() + "): "
                                + webhookUrlField.getText()
                );
            });
        });
    }

    private void onLiveFieldsChanged() {
        if (suppressLiveSync) {
            return;
        }
        suppressLiveSync = true;
        try {
            softApplyPathForServing();
            refreshWebhookUrl();
            updateStatusLabel();
        } finally {
            suppressLiveSync = false;
        }
    }

    /**
     * Keep the HTTP server path in sync with the deployable public URL path.
     */
    private void softApplyPathForServing() {
        String required = requiredStringField.getText();
        if (required == null || required.trim().isEmpty()) {
            return;
        }
        try {
            config.apply(required, pathField.getText(), config.getHtmlContent());
        } catch (IllegalArgumentException ignored) {
            // Incomplete draft while typing — URL preview still updates
        }
    }

    private void loadFromConfig() {
        suppressLiveSync = true;
        try {
            requiredStringField.setText(config.getRequiredString());
            pathField.setText(config.getWebhookPath());
            htmlArea.setText(config.getHtmlContent());
            publicWebhookToggle.setSelected(config.isEnabled());
            updateStatusLabel();
        } finally {
            suppressLiveSync = false;
        }
    }

    private void applySettings() {
        String required = requiredStringField.getText();
        String path = pathField.getText();
        String html = htmlArea.getText();

        if (required == null || required.trim().isEmpty()) {
            JOptionPane.showMessageDialog(
                    this,
                    "Required token cannot be empty.",
                    "Cannot apply",
                    JOptionPane.WARNING_MESSAGE
            );
            return;
        }

        try {
            String originalNormalized = WebhookConfig.normalizePath(path);
            boolean autoFixed = !WebhookConfig.pathContainsRequired(originalNormalized, required.trim());

            suppressLiveSync = true;
            String appliedPath;
            try {
                appliedPath = config.apply(required, path, html);
                pathField.setText(appliedPath);
                requiredStringField.setText(config.getRequiredString());
            } finally {
                suppressLiveSync = false;
            }

            refreshWebhookUrl();
            updateStatusLabel();

            if (autoFixed) {
                JOptionPane.showMessageDialog(
                        this,
                        "The required token was missing from the path and was prepended automatically.\n"
                                + "Applied path (also used in public Webhook URL): " + appliedPath,
                        "Path auto-corrected",
                        JOptionPane.INFORMATION_MESSAGE
                );
            } else {
                api.logging().logToOutput("[Webhook Page] Applied path: " + appliedPath);
            }
        } catch (IllegalArgumentException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Cannot apply", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void resetDefaults() {
        int confirm = JOptionPane.showConfirmDialog(
                this,
                "Reset required token, path, and HTML to defaults?",
                "Reset to default page",
                JOptionPane.YES_NO_OPTION
        );
        if (confirm != JOptionPane.YES_OPTION) {
            return;
        }
        config.resetToDefaults();
        loadFromConfig();
        refreshWebhookUrl();
    }

    private void refreshWebhookUrl() {
        try {
            int port = urlBuilder.getExtensionPort();
            extensionPortField.setText(port > 0
                    ? port + "  (" + urlBuilder.getExtensionBaseUrl() + ")"
                    : "(starting...)");

            if (!config.isEnabled()) {
                webhookUrlField.setText(WEBHOOK_OFF_PLACEHOLDER);
                publicStatusField.setText("Public: OFF | Source: none | URL: (Webhook OFF)");
                listenerHintLabel.setText(
                        "Public Webhook is OFF. Enable the toggle to auto-generate a ready-to-use URL "
                                + "(cloudflared / ngrok / LAN)."
                );
                return;
            }

            String current = webhookUrlField.getText();
            String existingPublic = config.getPublicAddress();
            if (GENERATING_PLACEHOLDER.equals(current)
                    && (existingPublic == null || existingPublic.isBlank())) {
                publicStatusField.setText("Public: ON | Source: discovering... | URL: (pending)");
                return;
            }

            String publicAddress = config.getPublicAddress();
            String required = requiredStringField.getText();
            String path = pathField.getText();
            String url = urlBuilder.buildDeployableUrl(publicAddress, required, path);
            webhookUrlField.setText(url);
            webhookUrlField.setCaretPosition(0);

            String sourceLabel = sourceDisplayName(lastSource);
            if (lastSource == PublicUrlService.Source.NONE && publicAddress != null && !publicAddress.isBlank()) {
                sourceLabel = "auto";
            }
            publicStatusField.setText(String.format(
                    "Public: ON | Source: %s | URL: %s",
                    sourceLabel,
                    publicAddress == null || publicAddress.isBlank() ? "(pending)" : publicAddress
            ));

            if (lastSource == PublicUrlService.Source.LAN) {
                listenerHintLabel.setText(
                        "LAN fallback active. For Internet access, install cloudflared or run: ngrok http "
                                + (port > 0 ? port : "<port>")
                );
            } else if (lastSource == PublicUrlService.Source.CLOUDFLARED) {
                listenerHintLabel.setText("Cloudflare Quick Tunnel active — URL is ready to use.");
            } else if (lastSource == PublicUrlService.Source.NGROK) {
                listenerHintLabel.setText("ngrok tunnel detected — URL is ready to use.");
            } else if (publicAddress == null || publicAddress.isBlank()) {
                listenerHintLabel.setText("Generating public URL...");
            }
        } catch (Exception e) {
            webhookUrlField.setText("(Failed to build URL: " + e.getMessage() + ")");
            api.logging().logToError("[Webhook Page] URL build failed: " + e.getMessage());
        }
    }

    private static String sourceDisplayName(PublicUrlService.Source source) {
        if (source == null) {
            return "none";
        }
        return switch (source) {
            case CLOUDFLARED -> "cloudflared";
            case NGROK -> "ngrok";
            case LAN -> "LAN";
            case NONE -> "none";
        };
    }

    private void copyUrl() {
        String url = webhookUrlField.getText();
        if (url == null || url.isBlank()
                || url.startsWith("(")
                || GENERATING_PLACEHOLDER.equals(url)
                || WEBHOOK_OFF_PLACEHOLDER.equals(url)) {
            JOptionPane.showMessageDialog(this, "No URL available to copy.", "Copy URL", JOptionPane.WARNING_MESSAGE);
            return;
        }
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(url), null);
        api.logging().logToOutput("[Webhook Page] URL copied: " + url);
        JOptionPane.showMessageDialog(this, "Webhook URL copied to clipboard.", "Copy URL", JOptionPane.INFORMATION_MESSAGE);
    }

    private void updateStatusLabel() {
        String state = config.isEnabled() ? "Enabled" : "Disabled";
        String publicAddress = config.getPublicAddress();
        String publicHint = (publicAddress == null || publicAddress.isBlank())
                ? (config.isEnabled() ? "(generating...)" : "(off)")
                : publicAddress;
        statusLabel.setText(String.format(
                "Status: %s  |  Applied path: %s  |  Required token: %s  |  Public: %s  |  Source: %s  |  Local port: %s",
                state,
                config.getWebhookPath(),
                config.getRequiredString(),
                publicHint,
                sourceDisplayName(lastSource),
                urlBuilder.getExtensionPort() > 0 ? urlBuilder.getExtensionPort() : "-"
        ));
        statusLabel.setForeground(config.isEnabled() ? new Color(46, 125, 50) : Color.RED);
    }
}
