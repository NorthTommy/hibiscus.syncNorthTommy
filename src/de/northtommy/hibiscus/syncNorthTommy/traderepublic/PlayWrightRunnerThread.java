package de.northtommy.hibiscus.syncNorthTommy.traderepublic;


import java.io.File;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.htmlunit.WebClient;
import org.json.JSONObject;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;

import de.northtommy.hibiscus.syncNorthTommy.SyncNTSynchronizeJobKontoauszugLoggerI;
import io.github.kihdev.playwright.stealth4j.Stealth4j;
import io.github.kihdev.playwright.stealth4j.Stealth4jConfig;

import de.willuhn.logging.Level;

/**
 * Loads the login page in a background browser only to obtain the values the API requires:
 * - the AWS WAF token (mp_verify/telemetry responses and x-aws-waf-token request header)
 * - the x-tr-* headers the web app sends (x-tr-device-info, x-tr-app-version, x-tr-platform)
 * No interaction with the page - the login itself is performed by the caller via HTTP.
 */
public class PlayWrightRunnerThread extends Thread {

    private static final String TRADEREP_API_HOST = "api.traderepublic.com";
    // interval for processing browser events while idle
    private static final int EVENT_POLL_INTERVAL_MS = 200;

    // current AWS WAF token (updated as long as the browser runs)
    public volatile String awsWafToken = null;
    // x-tr-* headers as sent by the web app (lower case keys)
    public volatile Map<String, String> trHeaders = Map.of();

    // Abbruchsteuerung
    private volatile boolean running = true;

    protected final SyncNTSynchronizeJobKontoauszugLoggerI syncJobLogger;
    protected final String firefoxPath;
    protected final boolean headlessBrowser;
    protected final WebClient webClient;
    protected final String urlToLoad;

    public PlayWrightRunnerThread(SyncNTSynchronizeJobKontoauszugLoggerI syncJobLogger, String firefoxPath, boolean headlessBrowser, WebClient webClient, String urlToLoad) {
        if (syncJobLogger == null) {
            throw new NullPointerException("syncJobLogger must not be null");
        }
        if (webClient == null) {
            throw new NullPointerException("webClient must not be null");
        }
        if (urlToLoad == null || (urlToLoad.isEmpty())) {
            throw new NullPointerException("urlToLoad must not be null or empty");
        }
        this.syncJobLogger = syncJobLogger;
        this.firefoxPath = firefoxPath;
        this.headlessBrowser = headlessBrowser;
        this.webClient = webClient;
        this.urlToLoad = urlToLoad;
    }

    /**
     * @return true as soon as token and device info are available
     */
    public boolean isReady() {
        var token = awsWafToken;
        return (token != null) && !token.isBlank() && trHeaders.containsKey("x-tr-device-info");
    }

    @Override
    public void run() {
        syncJobLogger.log(Level.INFO, "Browser for Playwright is getting started...");

        com.microsoft.playwright.Page pwPage = null;
        Browser browser = null;
        // filled by the response listener, body is read outside of the event handler
        var lastWafResponse = new AtomicReference<com.microsoft.playwright.Response>();
        try
        {
            Playwright playwright = Playwright.create();
            syncJobLogger.log(Level.INFO, "Playwright gestartet, waehle Browser...");
            var options1 = new BrowserType.LaunchOptions().setHeadless(headlessBrowser);
            var proxyConfig = webClient.getOptions().getProxyConfig();
            if (proxyConfig != null && proxyConfig.getProxyHost() != null)
            {
                var proxy = proxyConfig.getProxyScheme()+"://" + proxyConfig.getProxyHost() + ":" + proxyConfig.getProxyPort();
                options1.setProxy(proxy);
            }

            //String ffPath = null; // TODO konto.getMeta(TraderepublicSynchronizeBackend.META_FIREFOXPATH,  null);
            if (firefoxPath != null && !firefoxPath.isBlank())
            {
                var ffFile = new File(firefoxPath);
                if (ffFile.isFile())
                {
                    if (ffFile.canExecute())
                    {
                        syncJobLogger.log(Level.INFO, "Verwende Firefox unter " + firefoxPath);
                        options1 = options1.setExecutablePath(Path.of(firefoxPath));
                    }
                    else
                    {
                        syncJobLogger.log(Level.WARN, "Firefox-Pfad auf " + firefoxPath + " gesetzt, aber Datei nicht ausf\u00FChrbar");
                    }
                }
                else
                {
                    syncJobLogger.log(Level.WARN, "Firefox-Pfad auf " + firefoxPath + " gesetzt, aber Datei nicht vorhanden");
                }
            }
            else
            {
                syncJobLogger.log(Level.INFO, "Verwende Firefox von Playwright");
            }

            syncJobLogger.log(Level.INFO, "Playwright gestartet, starte Browser...");
            browser = playwright.firefox().launch(options1);

            syncJobLogger.log(Level.INFO, "Playwright gestartet, stealth context");
            var stealthContext = Stealth4j.newStealthContext(browser, Stealth4jConfig.builder().navigatorLanguages(true, List.of("de-DE", "de")).build());
            stealthContext.setExtraHTTPHeaders(Map.of("DNT", "1"));
            pwPage = stealthContext.newPage();

            // listeners are registered before navigate: the first token and the first API request
            // (qr-challenges) happen around the load event
            pwPage.onResponse(r -> {
                if (isWafTokenResponse(r)) {
                    lastWafResponse.set(r);
                }
            });
            pwPage.onRequest(r -> {
                if (r.url().contains(TRADEREP_API_HOST)) {
                    // headers() is non-blocking and contains the custom x-* headers
                    takeOverApiRequestHeaders(r.headers());
                }
            });

            syncJobLogger.log(Level.INFO, "Navigate to: " + urlToLoad);
            pwPage.navigate(urlToLoad);
        }
        catch (Exception e)
        {
            syncJobLogger.log(Level.WARN, "Error starting Playwright: " + e.getMessage());
            if (pwPage != null) pwPage.close();
            if (browser != null) browser.close();
            // break the Thread may lead to timeout waiting for token from outside - we cannot directly throw an exception
            return;
        }

        try {
            while (running) {
                var response = lastWafResponse.getAndSet(null);
                if (response != null) {
                    try {
                        updateWafTokenFromResponse(response);
                    } catch (Exception e) {
                        // a single unreadable response is not critical, the next one will follow
                        syncJobLogger.log(Level.DEBUG, "unable to read AWS WAF token response: " + e.getMessage());
                    }
                }
                // Playwright dispatches events (listeners) only during API calls - Thread.sleep() would not
                pwPage.waitForTimeout(EVENT_POLL_INTERVAL_MS);
            }
            syncJobLogger.log(Level.INFO, "PlayWrightRunnerThread wird beendet");
        } catch (Exception e) {
            if (running) {
                syncJobLogger.log(Level.WARN, "PlayWrightRunnerThread Fehler beim Token-Lesen: " + e.getMessage());
            }
        } finally {
            syncJobLogger.log(Level.INFO, "PlayWrightRunnerThread close browser");
            if (pwPage != null) pwPage.close();
            if (browser != null) browser.close();
            syncJobLogger.log(Level.INFO, "PlayWrightRunnerThread browser closed");
        }
    }

    /**
     * Responses of the AWS WAF challenge script containing a token:
     * mp_verify (first token after challenge) and telemetry (refreshed tokens).
     */
    private static boolean isWafTokenResponse(com.microsoft.playwright.Response r) {
        var url = r.url();
        return (url.indexOf("token.awswaf.com") != -1) &&
               ((url.indexOf("/telemetry") != -1) || (url.indexOf("/mp_verify") != -1));
    }

    private void updateWafTokenFromResponse(com.microsoft.playwright.Response response) {
        var json = new JSONObject(response.text());
        var token = json.optString("token");
        if (!token.isBlank()) {
            setWafToken(token, "WAF response");
        }
    }

    /**
     * Takes over x-tr-* headers and the AWS WAF token of a request the web app sends to the API.
     */
    private void takeOverApiRequestHeaders(Map<String, String> headers) {
        var newTrHeaders = new HashMap<String, String>(trHeaders);
        headers.forEach((key, value) -> {
            var k = key.toLowerCase();
            if (k.startsWith("x-tr-")) {
                newTrHeaders.put(k, value);
            } else if (k.equals("x-aws-waf-token") && !value.isBlank()) {
                setWafToken(value, "API request");
            }
        });
        if (!newTrHeaders.equals(trHeaders)) {
            trHeaders = Map.copyOf(newTrHeaders);
            syncJobLogger.log(Level.DEBUG, "x-tr headers taken over from web app: " + newTrHeaders.keySet());
        }
    }

    private void setWafToken(String token, String source) {
        if (!token.equals(awsWafToken)) {
            awsWafToken = token;
            syncJobLogger.log(Level.DEBUG, "Aktueller AWS WAF token (" + source + "): " + token);
        }
    }

    // Methode zum Stoppen von außen
    public void stopRunning() {
        running = false;
    }
}
