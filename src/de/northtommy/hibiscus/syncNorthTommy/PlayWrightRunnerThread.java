package de.northtommy.hibiscus.syncNorthTommy;


import java.io.File;
import java.nio.file.Path;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.htmlunit.WebClient;
import org.json.JSONObject;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Browser.NewContextOptions;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Page.ScreenshotOptions;
import com.microsoft.playwright.Route.FulfillOptions;
import com.microsoft.playwright.options.ScreenshotAnimations;
import com.microsoft.playwright.options.ScreenshotType;

import de.northtommy.hibiscus.syncNorthTommy.traderepublic.TraderepublicSynchronizeBackend;
import io.github.kihdev.playwright.stealth4j.Stealth4j;
import io.github.kihdev.playwright.stealth4j.Stealth4jConfig;

import de.willuhn.logging.Level;
import de.willuhn.jameica.hbci.rmi.Konto;

public class PlayWrightRunnerThread extends Thread {

    // öffentliches Feld wie gewünscht
    public volatile String awsWafToken = null;

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
    
    @Override
    public void run() {
        syncJobLogger.log(Level.INFO, "Browser for Playwright is getting started...");
        
        com.microsoft.playwright.Page pwPage = null;
		Browser browser = null;
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
            	var response = pwPage.waitForResponse(r -> 
					(r.url().indexOf("telemetry") != -1), () -> {}
				);
						
				//String url = response.url();
				//syncJob.log(Level.INFO, "url: " + url);
				
				String resp = response.text();
				var json = new JSONObject(resp);
				
				// try to find the token inside the response and update the stored token
				awsWafToken = json.optString("token");
				syncJobLogger.log(Level.INFO, "got new AWS WAF token: " + awsWafToken);
                if (awsWafToken != null) {
                    syncJobLogger.log(Level.DEBUG, "Aktueller AWS WAF token: " + awsWafToken);
                }
                Thread.yield();
                Thread.sleep(1);
            }
            syncJobLogger.log(Level.INFO, "PlayWrightRunnerThread wird beendet");
        } catch (InterruptedException e) {
            syncJobLogger.log(Level.WARN, "PlayWrightRunnerThread wurde unterbrochen");
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            syncJobLogger.log(Level.WARN, "PlayWrightRunnerThread Fehler beim Token-Lesen: " + e.getMessage());
        } finally {
        	syncJobLogger.log(Level.INFO, "PlayWrightRunnerThread close browser");
        	if (pwPage != null) pwPage.close();
			if (browser != null) browser.close();
            syncJobLogger.log(Level.INFO, "PlayWrightRunnerThread browser closed");
        }
    }

    // Methode zum Stoppen von außen
    public void stopRunning() {
        running = false;
    }
}