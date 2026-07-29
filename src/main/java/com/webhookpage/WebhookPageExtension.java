package com.webhookpage;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import com.webhookpage.ui.WebhookTab;

import javax.swing.SwingUtilities;

/**
 * Burp Suite Extension — serves a custom HTML webhook page from a dedicated
 * local HTTP server (does not intercept Burp Proxy traffic).
 */
public class WebhookPageExtension implements BurpExtension {

    public static final String EXTENSION_NAME = "Webhook Page";

    private LocalWebhookServer localServer;
    private PublicUrlService publicUrlService;

    @Override
    public void initialize(MontoyaApi api) {
        api.extension().setName(EXTENSION_NAME);
        api.logging().logToOutput("Webhook Page Extension loaded.");

        WebhookConfig config = new WebhookConfig();
        WebhookUrlBuilder urlBuilder = new WebhookUrlBuilder(api, config);
        publicUrlService = new PublicUrlService();

        WebhookTab[] tabHolder = new WebhookTab[1];

        try {
            localServer = new LocalWebhookServer(config, log -> {
                WebhookTab tab = tabHolder[0];
                if (tab != null) {
                    tab.addLogEntry(log);
                }
                api.logging().logToOutput("[Webhook Page] Hit: " + log.getMethod() + " " + log.getUrl());
            });
            urlBuilder.setExtensionPort(localServer.getPort());
            api.logging().logToOutput("Webhook server listening on " + localServer.getLocalBaseUrl());
            api.logging().logToOutput("Public Webhook defaults to OFF. Enable the toggle in the tab to auto-generate a public URL.");
            api.logging().logToOutput("NOTE: Do NOT use Burp Proxy port (e.g. 8080). Point tunnels at the Extension port above.");
        } catch (Exception e) {
            api.logging().logToError("Failed to start webhook server: " + e.getMessage());
            throw new IllegalStateException("Failed to start webhook server", e);
        }

        SwingUtilities.invokeLater(() -> {
            WebhookTab tab = new WebhookTab(api, config, urlBuilder, publicUrlService);
            tabHolder[0] = tab;
            api.userInterface().registerSuiteTab(EXTENSION_NAME, tab);
        });

        api.extension().registerUnloadingHandler(() -> {
            if (publicUrlService != null) {
                publicUrlService.close();
                api.logging().logToOutput("Public URL tunnels stopped.");
            }
            if (localServer != null) {
                localServer.close();
                api.logging().logToOutput("Webhook server stopped.");
            }
        });
    }
}
