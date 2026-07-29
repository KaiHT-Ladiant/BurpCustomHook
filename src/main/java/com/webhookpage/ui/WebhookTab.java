package com.webhookpage.ui;

import burp.api.montoya.MontoyaApi;
import com.webhookpage.CloudflaredResolver;
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
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
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
import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Main suite tab UI for Webhook Page extension.
 */
public final class WebhookTab extends JPanel {

    private static final String WEBHOOK_OFF_PLACEHOLDER = "(Webhook OFF)";
    private static final String GENERATING_PLACEHOLDER = "Generating...";
    private static final String EXTENSION_VERSION = "1.0.6";

    private final MontoyaApi api;
    private final WebhookConfig config;
    private final WebhookUrlBuilder urlBuilder;
    private final PublicUrlService publicUrlService;
    private final RequestLogTableModel logModel;
    private final AtomicInteger discoveryGeneration = new AtomicInteger();

    private final JCheckBox publicWebhookToggle = new JCheckBox("Public Webhook (available ON/OFF)");
    private final JTextField tunnelDomainField = new JTextField();
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

        setLayout(new BorderLayout(6, 6));
        setBorder(new EmptyBorder(8, 8, 8, 8));

        // Vertical split keeps Response HTML visible; previous NORTH+CENTER+SOUTH
        // crushed the HTML editor under the taller Tunnel domain panel + request log.
        JPanel upper = new JPanel(new BorderLayout(6, 6));
        upper.add(buildTopPanel(), BorderLayout.NORTH);
        upper.add(buildCenterPanel(), BorderLayout.CENTER);

        JSplitPane mainSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, upper, buildLogPanel());
        mainSplit.setResizeWeight(0.78);
        mainSplit.setContinuousLayout(true);
        mainSplit.setBorder(null);
        add(mainSplit, BorderLayout.CENTER);

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
                "ON: start a tunnel to this Extension listen port and show a deployable HTTPS URL "
                        + "(cloudflared → optional ngrok you already run → LAN fallback). "
                        + "OFF: matching path returns 503; stop tunnels this extension started. "
                        + "Independent of PortSwigger Burp Collaborator."
        );
        publicWebhookToggle.addActionListener(e -> onPublicWebhookToggled());

        JLabel versionLabel = new JLabel("Webhook Page " + EXTENSION_VERSION);
        versionLabel.setFont(versionLabel.getFont().deriveFont(Font.PLAIN, 11f));
        versionLabel.setForeground(new Color(100, 100, 100));

        JPanel toggleRow = new JPanel(new BorderLayout());
        toggleRow.add(publicWebhookToggle, BorderLayout.WEST);
        toggleRow.add(versionLabel, BorderLayout.EAST);

        JLabel domainTitle = new JLabel("Tunnel domain (hostname only)");
        domainTitle.setFont(domainTitle.getFont().deriveFont(Font.BOLD, 12f));
        tunnelDomainField.setEditable(false);
        tunnelDomainField.setFont(new Font(Font.MONOSPACED, Font.BOLD, 14));
        tunnelDomainField.setBackground(UIManager.getColor("TextField.background"));
        tunnelDomainField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(33, 150, 243), 2),
                new EmptyBorder(4, 8, 4, 8)
        ));
        tunnelDomainField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        tunnelDomainField.setToolTipText(
                "Hostname from cloudflared (*.trycloudflare.com) or ngrok when a tunnel is up. "
                        + "LAN IP is not shown here as a tunnel domain."
        );

        JLabel title = new JLabel("Full Webhook URL (tunnel/LAN base + required token path)");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 11f));

        webhookUrlField.setEditable(false);
        webhookUrlField.setFont(new Font(Font.MONOSPACED, Font.BOLD, 12));
        webhookUrlField.setBackground(UIManager.getColor("TextField.background"));
        webhookUrlField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(76, 175, 80), 2),
                new EmptyBorder(4, 8, 4, 8)
        ));
        webhookUrlField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));

        JButton copyBtn = new JButton("Copy URL");
        copyBtn.addActionListener(e -> copyUrl());

        JButton copyDomainBtn = new JButton("Copy domain");
        copyDomainBtn.addActionListener(e -> copyDomain());

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
        urlButtons.add(copyDomainBtn);
        urlButtons.add(Box.createVerticalStrut(6));
        urlButtons.add(copyBtn);
        urlButtons.add(Box.createVerticalStrut(6));
        urlButtons.add(refreshBtn);

        JPanel urlFieldWrap = new JPanel(new BorderLayout(0, 4));
        urlFieldWrap.add(toggleRow, BorderLayout.NORTH);
        JPanel titleAndUrl = new JPanel();
        titleAndUrl.setLayout(new BoxLayout(titleAndUrl, BoxLayout.Y_AXIS));
        titleAndUrl.add(domainTitle);
        titleAndUrl.add(Box.createVerticalStrut(4));
        titleAndUrl.add(tunnelDomainField);
        titleAndUrl.add(Box.createVerticalStrut(8));
        titleAndUrl.add(title);
        titleAndUrl.add(Box.createVerticalStrut(4));
        titleAndUrl.add(webhookUrlField);
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
        JPanel pathForm = new JPanel(new GridBagLayout());
        pathForm.setBorder(new EmptyBorder(4, 4, 4, 4));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 4, 3, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;

        c.gridx = 0;
        c.gridy = 0;
        c.weightx = 0;
        pathForm.add(new JLabel("Required token:"), c);
        c.gridx = 1;
        c.weightx = 1;
        requiredStringField.setToolTipText(
                "Must appear in the deployable (public) Webhook URL path (default: kai_ht)."
        );
        pathForm.add(requiredStringField, c);

        c.gridx = 0;
        c.gridy = 1;
        c.weightx = 0;
        pathForm.add(new JLabel("Webhook path:"), c);
        c.gridx = 1;
        c.weightx = 1;
        pathField.setToolTipText(
                "e.g. /kai_ht/webhook — required token is forced into the public URL path automatically."
        );
        pathForm.add(pathField, c);

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
        pathForm.add(buttons, c);

        c.gridy = 3;
        updateStatusLabel();
        pathForm.add(statusLabel, c);

        htmlArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        htmlArea.setLineWrap(false);
        htmlArea.setTabSize(4);
        JScrollPane htmlScroll = new JScrollPane(htmlArea);
        htmlScroll.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        htmlScroll.setMinimumSize(new Dimension(200, 160));

        JPanel htmlTab = new JPanel(new BorderLayout());
        htmlTab.add(new JLabel("Edit the HTML returned for matching webhook requests, then click Apply."), BorderLayout.NORTH);
        htmlTab.add(htmlScroll, BorderLayout.CENTER);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Response HTML", htmlTab);
        tabs.addTab("Path / Token", pathForm);
        tabs.setSelectedIndex(0);

        JPanel settings = new JPanel(new BorderLayout(4, 4));
        settings.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(),
                "Webhook page",
                TitledBorder.LEFT,
                TitledBorder.TOP
        ));
        settings.add(tabs, BorderLayout.CENTER);
        settings.setMinimumSize(new Dimension(200, 220));
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
        panel.setMinimumSize(new Dimension(200, 100));
        panel.setPreferredSize(new Dimension(100, 140));

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

        tunnelDomainField.setText(GENERATING_PLACEHOLDER);
        webhookUrlField.setText(GENERATING_PLACEHOLDER);
        publicStatusField.setText("Public: ON | Source: discovering... | Domain: (pending)");
        listenerHintLabel.setText("Generating tunnel domain (cloudflared → ngrok → LAN)...");
        api.logging().logToOutput("[Webhook Page] Public Webhook ON — discovering tunnel domain...");

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
                        "[Webhook Page] Public ready (" + result.source() + ") domain="
                                + tunnelDomainField.getText() + " url=" + webhookUrlField.getText()
                );
                if (result.source() == PublicUrlService.Source.LAN) {
                    String fail = publicUrlService.getLastTunnelFailure();
                    String reason = (fail == null || fail.isBlank()) ? result.hint() : fail;
                    JOptionPane.showMessageDialog(
                            WebhookTab.this,
                            "Internet tunnel domain was not created.\n\n"
                                    + "Tunnel domain shows LAN-only because cloudflared/ngrok failed.\n"
                                    + "This is a fallback, not the intended public hostname.\n\n"
                                    + "Reason:\n" + reason + "\n\n"
                                    + "If download failed (handshake / .tmp):\n"
                                    + "1) Download official cloudflared from GitHub Releases\n"
                                    + "2) Save as:\n   "
                                    + CloudflaredResolver.cachedBinaryPath() + "\n"
                                    + "3) Click Refresh URL\n\n"
                                    + "Log (if any):\n  "
                                    + System.getProperty("user.home")
                                    + "\\.webhook-page\\logs\\quick-tunnel.log\n\n"
                                    + "This tunnel helper is independent of PortSwigger Burp Collaborator.",
                            "Webhook Page " + EXTENSION_VERSION + " — no tunnel domain",
                            JOptionPane.WARNING_MESSAGE
                    );
                }
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
                tunnelDomainField.setText(WEBHOOK_OFF_PLACEHOLDER);
                webhookUrlField.setText(WEBHOOK_OFF_PLACEHOLDER);
                publicStatusField.setText("Public: OFF | Source: none | Domain: (off)");
                listenerHintLabel.setText(
                        "Public Webhook is OFF. Turn ON to create a tunnel domain "
                                + "(*.trycloudflare.com via cloudflared, or your ngrok host)."
                );
                return;
            }

            String current = webhookUrlField.getText();
            String existingPublic = config.getPublicAddress();
            if (GENERATING_PLACEHOLDER.equals(current)
                    && (existingPublic == null || existingPublic.isBlank())) {
                tunnelDomainField.setText(GENERATING_PLACEHOLDER);
                publicStatusField.setText("Public: ON | Source: discovering... | Domain: (pending)");
                return;
            }

            String publicAddress = config.getPublicAddress();
            String required = requiredStringField.getText();
            String path = pathField.getText();
            String url = urlBuilder.buildDeployableUrl(publicAddress, required, path);
            webhookUrlField.setText(url);
            webhookUrlField.setCaretPosition(0);

            String domainDisplay = formatTunnelDomain(publicAddress, lastSource);
            tunnelDomainField.setText(domainDisplay);
            tunnelDomainField.setCaretPosition(0);

            String sourceLabel = sourceDisplayName(lastSource);
            if (lastSource == PublicUrlService.Source.NONE && publicAddress != null && !publicAddress.isBlank()) {
                sourceLabel = "auto";
            }
            publicStatusField.setText(String.format(
                    "Public: ON | Source: %s | Domain: %s",
                    sourceLabel,
                    domainDisplay
            ));

            if (lastSource == PublicUrlService.Source.LAN) {
                listenerHintLabel.setText(
                        "No tunnel domain — LAN fallback only. Point cloudflared/ngrok at Extension listen port "
                                + (port > 0 ? port : "?")
                                + ", then Refresh URL."
                );
            } else if (lastSource == PublicUrlService.Source.CLOUDFLARED) {
                listenerHintLabel.setText(
                        "Cloudflare Quick Tunnel active — use the Tunnel domain / Full Webhook URL above."
                );
            } else if (lastSource == PublicUrlService.Source.NGROK) {
                listenerHintLabel.setText("ngrok tunnel detected — Tunnel domain is ready.");
            } else if (publicAddress == null || publicAddress.isBlank()) {
                listenerHintLabel.setText("Generating tunnel domain...");
            }
        } catch (Exception e) {
            tunnelDomainField.setText("(error)");
            webhookUrlField.setText("(Failed to build URL: " + e.getMessage() + ")");
            api.logging().logToError("[Webhook Page] URL build failed: " + e.getMessage());
        }
    }

    /**
     * Shows hostname for real tunnels; LAN fallback is explicitly not a tunnel domain.
     */
    private String formatTunnelDomain(String publicAddress, PublicUrlService.Source source) {
        if (source == PublicUrlService.Source.LAN) {
            String fail = publicUrlService.getLastTunnelFailure();
            if (fail != null && !fail.isBlank()) {
                String shortFail = fail.length() > 120 ? fail.substring(0, 120) + "…" : fail;
                return "(none — LAN fallback) " + shortFail;
            }
            return "(none — LAN fallback; tunnel failed — see Extender output)";
        }
        if (publicAddress == null || publicAddress.isBlank()) {
            return "(pending)";
        }
        try {
            URI uri = URI.create(publicAddress.trim());
            String host = uri.getHost();
            if (host != null && !host.isBlank()) {
                int p = uri.getPort();
                if (p > 0 && p != 80 && p != 443) {
                    return host + ":" + p;
                }
                return host;
            }
        } catch (Exception ignored) {
            // fall through
        }
        String trimmed = publicAddress.trim();
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            int scheme = trimmed.indexOf("://");
            String rest = trimmed.substring(scheme + 3);
            int slash = rest.indexOf('/');
            return slash >= 0 ? rest.substring(0, slash) : rest;
        }
        return trimmed;
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

    private void copyDomain() {
        String domain = tunnelDomainField.getText();
        if (domain == null || domain.isBlank()
                || domain.startsWith("(")
                || GENERATING_PLACEHOLDER.equals(domain)
                || WEBHOOK_OFF_PLACEHOLDER.equals(domain)) {
            JOptionPane.showMessageDialog(
                    this,
                    "No tunnel domain to copy.\n"
                            + "Turn Public Webhook ON and wait for cloudflared/ngrok.\n"
                            + "(LAN fallback does not provide a tunnel domain.)",
                    "Copy domain",
                    JOptionPane.WARNING_MESSAGE
            );
            return;
        }
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(domain), null);
        api.logging().logToOutput("[Webhook Page] Tunnel domain copied: " + domain);
        JOptionPane.showMessageDialog(this, "Tunnel domain copied to clipboard.", "Copy domain", JOptionPane.INFORMATION_MESSAGE);
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
