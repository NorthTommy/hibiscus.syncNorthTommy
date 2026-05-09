package de.northtommy.hibiscus.syncNorthTommy.traderepublic;


import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.htmlunit.WebClient;
import org.json.JSONObject;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import de.northtommy.hibiscus.syncNorthTommy.SyncNTSynchronizeJobKontoauszugLoggerI;
import io.github.kihdev.playwright.stealth4j.Stealth4j;
import io.github.kihdev.playwright.stealth4j.Stealth4jConfig;

import de.willuhn.logging.Level;

public class PlayWrightRunnerThread extends Thread {

	private static final String TRADEREP_PLAYWRIGTH_LOGIN = "https://app.traderepublic.com/login";
	
	//private static final String TRADEREP_LOGIN_URL = "https://api.traderepublic.com/api/v2/auth/web/login";
	//private static final String TRADEREP_LOGIN_PROGRESS_URL = "https://api.traderepublic.com/api/v2/auth/web/login/processes";

	
    // öffentliches Feld wie gewünscht
    public volatile String awsWafToken = null;
    public volatile String sessId = null;
    public volatile String tr_session = null;
    public volatile String tr_claims = null;
    public volatile String tr_refresh = null;
    public volatile String tr_device = null;
    public volatile String tr_external_id = null;
    public volatile boolean notifyUserConfirm = false;

    // Abbruchsteuerung
    private volatile boolean running = true;

    protected final SyncNTSynchronizeJobKontoauszugLoggerI syncJobLogger;
    protected final String firefoxPath;
	protected final boolean headlessBrowser;
    protected final WebClient webClient;
    protected final String user;
    protected final String pin;
    
    public PlayWrightRunnerThread(SyncNTSynchronizeJobKontoauszugLoggerI syncJobLogger, String firefoxPath, boolean headlessBrowser, WebClient webClient, String user, String pin) {
		if (syncJobLogger == null) {
			throw new NullPointerException("syncJobLogger must not be null");
		}
		if (webClient == null) {
			throw new NullPointerException("webClient must not be null");
		}
		if (user == null || (user.isEmpty())) {
			throw new NullPointerException("user must not be null or empty");
		}
		if (pin == null || (pin.isEmpty())) {
			throw new NullPointerException("pin must not be null or empty");
		}
	    	this.syncJobLogger = syncJobLogger;
	    	this.firefoxPath = firefoxPath;
	    	this.headlessBrowser = headlessBrowser;
	    	this.webClient = webClient;
	    	this.user = user;
	    	this.pin = pin;
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
			
			syncJobLogger.log(Level.INFO, "Navigate to: " + TRADEREP_PLAYWRIGTH_LOGIN);
			pwPage.navigate(TRADEREP_PLAYWRIGTH_LOGIN);		
			
			
			
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
        	boolean firstTime = true;
            while (running) {
            		var response = pwPage.waitForResponse(r -> 
					((r.url().indexOf("telemetry") != -1) || 
					 (r.url().indexOf("login/processes") != -1) ), () -> {}
				);
						
            		if (response.url().indexOf("telemetry") != -1) {
					String resp = response.text();
					var json = new JSONObject(resp);
					
					// try to find the token inside the response and update the stored token
					awsWafToken = json.optString("token");
					syncJobLogger.log(Level.INFO, "got new AWS WAF token: " + awsWafToken);
	                if (awsWafToken != null) {
	                    syncJobLogger.log(Level.DEBUG, "Aktueller AWS WAF token: " + awsWafToken);
	                }
            		}
            		if (response.url().indexOf("login/processes") != -1) {
            			syncJobLogger.log(Level.INFO, "login poll status: " + response.text());
            			String resp = response.text();
    					var json = new JSONObject(resp);
            			var loginStatus = json.getString("status");
            			if (loginStatus.compareToIgnoreCase("PENDING") != 0) {
            				if (loginStatus.compareToIgnoreCase("CONFIRMED") == 0) {
            					response.headers().forEach((key,value) -> {
            						//syncJobLogger.log(Level.DEBUG, "Login polling header: " + key + ": " + value);
            						if (key.compareToIgnoreCase("set-cookie") == 0) {
            							String[] cookies = value.split("\n");
            							for (String cookie : cookies) {
            								//syncJobLogger.log(Level.DEBUG, "Login polling header set cookie: " + cookie);
            								if (cookie.startsWith("JSESSIONID")) {
            									sessId = cookie.substring(11 /*"JSESSIONID="*/, cookie.indexOf(";")).trim();
            								}
            								if (cookie.startsWith("tr_session")) {
            									tr_session = cookie.substring(11 /*"tr_session="*/, cookie.indexOf(";")).trim();
            								}
            								if (cookie.startsWith("tr_claims")) {
            									tr_claims = cookie.substring(10 /*"tr_claims="*/, cookie.indexOf(";")).trim(); 
            								}
            								if (cookie.startsWith("tr_refresh")) {
            									tr_refresh = cookie.substring(11 /*"tr_refresh="*/, cookie.indexOf(";")).trim();
            								}
            								if (cookie.startsWith("tr_device")) {
            									tr_device = tr_refresh = cookie.substring(11 /*"tr_device="*/, cookie.indexOf(";")).trim();
            								}
            								if (cookie.startsWith("tr_external_id")) {
            									tr_external_id = tr_refresh = cookie.substring(15 /*"tr_external_id="*/, cookie.indexOf(";")).trim();
            								}
            							}
            						}
            					});
            				}
            				else {
            					throw new Exception("Login not confirmed in time by user");
            				}
            			}
            		}
            		
                Thread.yield();
                Thread.sleep(1);
                
                if (firstTime) {
                		firstTime = false;
	                Thread.sleep(1000);
	                
	                pwPage.locator("form[class='consentCard__form']").locator("xpath=.//button[1]").click();
	                
	                Thread.sleep(1000);
	                var phoneno = user;
	                // website is fixed with +49 prepared and accepts only the number without leading 
	                if (user.startsWith("+")) {
	                		//+49 172
	                		phoneno = phoneno.substring(3);
	                } else if (user.startsWith("00")) {
	                		//0049 172...
                			phoneno = phoneno.substring(4);
	                } else if (user.startsWith("0")) {
	                		// 0172....
	                		phoneno = phoneno.substring(1);
	                }  
	                pwPage.locator("input[id='loginPhoneNumber__input']")
	                 .fill(phoneno);
	
	                pwPage.locator("input[id='loginPhoneNumber__input']")
	                 .locator("xpath=ancestor::form")
	                 .locator("button[type='submit']")
	                 .click();
	                
	                Thread.sleep(1000);
	                // press the pin   
	                for (int i = 0; i < pin.length(); i++) {
	                		pwPage.locator("form[class='loginPin__form']").press(pin.substring(i,i+1));
	                }
	                
	                // outer thread can display notification now....
	                notifyUserConfirm = true;
                }
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