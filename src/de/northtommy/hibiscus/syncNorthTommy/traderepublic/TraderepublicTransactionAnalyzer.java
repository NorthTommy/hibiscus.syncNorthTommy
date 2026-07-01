package de.northtommy.hibiscus.syncNorthTommy.traderepublic;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

final class TraderepublicTransactionAnalyzer {
	private static List<String> knownEvents = Arrays.asList("CARD_REFUND", "CARD_VERIFICATION", "CARD_TRANSACTION",
			"BANK_TRANSACTION_INCOMING", "INTEREST_PAYOUT", "BANK_TRANSACTION_OUTGOING", "INTEREST_PAYOUT_CREATED",
			"PAYMENT_INBOUND_CREDIT_CARD", "SSP_CORPORATE_ACTION_CASH", "SSP_CORPORATE_ACTION_CASH_NON_DIVIDEND");
	private static List<String> relevantList = Arrays.asList("SAVINGS_PLAN_INVOICE_CREATED", "TRADE_INVOICE",
			"SAVEBACK_AGGREGATE", "TRADING_SAVINGSPLAN_EXECUTED", "TRADING_TRADE_EXECUTED");

	private TraderepublicTransactionAnalyzer() {
	}

	public static String getId(JSONObject details) {
		return getSection(details, "id").toString();
	}

	public static String getEventType(JSONObject transaction) {
		return optString(transaction, "eventType");
	}

	public static boolean knownEventType(JSONObject transaction) {
		String eventType = optString(transaction, "eventType");
		return knownEvents.contains(eventType) || relevantList.contains(eventType);
	}

	public static boolean relevantEventType(JSONObject transaction) {
		String eventType = optString(transaction, "eventType");
		return relevantList.contains(eventType);
	}

	public static HashMap<String, Object> parseTransaction(JSONObject transaction, JSONObject details)
			throws ParseException {

		String id = getSection(details, "id").toString();
		String eventType = optString(transaction, "eventType");
		String timestamp = getSection(transaction, "timestamp").toString();

		Double count = normalize(firstNonBlank(
				getSection(details, "sections", "Transaction", "data", "Shares", "detail", "text"),
				getSection(details, "sections", "Overview", "data", "Transaction", "detail", "action", "payload",
						"sections", "[1]", "data", "Shares", "detail", "text"),
				getSection(details, "sections", "Overview", "data", "Transaction", "detail", "displayValue",
						"prefix")));
		String a = firstNonBlank(
				getSection(details, "sections", "Transaction", "data", "Share price", "detail", "text"),
				getSection(details, "sections", "Overview", "data", "Transaction", "detail", "action", "payload",
						"sections", "[1]", "data", "Share price", "detail", "text"),
				getSection(details, "sections", "Overview", "data", "Transaction", "detail", "displayValue", "text"));
		Double sharePrice = normalize(firstNonBlank(
				getSection(details, "sections", "Transaction", "data", "Share price", "detail", "text"),
				getSection(details, "sections", "Overview", "data", "Transaction", "detail", "action", "payload",
						"sections", "[1]", "data", "Share price", "detail", "text"),
				getSection(details, "sections", "Overview", "data", "Transaction", "detail", "displayValue", "text")));
		Double total = normalize(firstNonBlank(
				getSection(details, "sections", "Transaction", "data", "Total", "detail", "text"),
				getSection(details, "sections", "Overview", "data", "Transaction", "detail", "action", "payload",
						"sections", "[1]", "data", "Total", "detail", "text"),
				getSection(details, "sections", "Overview", "data", "Total", "detail", "text")));
		Double fee = normalize(firstNonBlank(
				getSection(details, "sections", "Transaction", "data", "Fee", "detail", "text"),
				getSection(details, "sections", "Overview", "data", "Fee", "detail", "text")).replace("Free", "0"));
		Double tax = normalize(firstNonBlank(
				getSection(details, "sections", "Overview", "data", "Tax", "detail", "text"),
				"0"));
		String name = getSection(transaction, "title").toString();
		String isin = getSection(details, "sections", "action", "payload").toString();
		String orderType = firstNonBlank(
				getSection(details, "sections", "Overview", "data", "Order Type", "detail", "text"));
		if (orderType == null) {
			if ("Sell".equals(getSection(details, "sections", "Overview", "data", "[0]", "title"))) {
				orderType = "Sell";
			}
			if (orderType == null && (eventType.startsWith("SAVEBACK_AGGREGATE")
					|| eventType.startsWith("TRADING_SAVINGSPLAN_EXECUTED"))) {
				orderType = "Buy";
			}
		}
		orderType = orderType.replace("Savings plan", "Buy");
		if (!(orderType.equals("Buy") || orderType.equals("Sell"))) {
			throw new ParseException("Unbekannter Ordertype " + orderType + " (" + id + ")", 0);
		}
		Double kosten;
		// Bei Käufen muss der Gesamtbetrag negativ sein, beim Verkauf positiv.
		if (orderType.equals("Buy")) {
			kosten = -(fee + sharePrice * count);
		} else {
			kosten = sharePrice * count - fee - tax;
		}
		// Cachback für Kreditkarte passend behandeln.
		if ("SAVEBACK_AGGREGATE".equals(eventType)) {
			kosten = 0.0;
		}
		SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ");

		HashMap<String, Object> t = new HashMap<>();
		t.put("datetime", dateFormat.parse(timestamp));
		t.put("orderid", transaction.optString("id"));
		t.put("isin", isin);
		t.put("name", name);
		t.put("kosten", kosten);
		t.put("kostenw", "EUR");
		t.put("anzahl", count);
		t.put("gebuehren", fee);
		t.put("gebuehrenw", "EUR");
		t.put("steuern", tax);
		t.put("steuernw", "EUR");
		t.put("kurs", sharePrice);
		t.put("kursw", "EUR");
		t.put("aktion", orderType);
		return t;
	}

	public static Object getSection(Object info, String... sectionPath) {
		if (info == null) {
			return null;
		}
		if (sectionPath.length == 0) {
			return info;
		}
		String head = sectionPath[0];
		String[] tail = new String[sectionPath.length - 1];
		System.arraycopy(sectionPath, 1, tail, 0, tail.length);

		if (info instanceof JSONObject jsonObject) {
			if (jsonObject.has(head)) {
				return getSection(jsonObject.get(head), tail);
			}
			if (jsonObject.has("title") && head.equals(jsonObject.optString("title"))) {
				return getSection(jsonObject, tail);
			}
			return null;
		}
		if (info instanceof JSONArray jsonArray) {
			Pattern pattern = Pattern.compile("\\[(\\d+)\\]");
			Matcher matcher = pattern.matcher(head);
			if (matcher.find()) {
				Object item = jsonArray.get(Integer.valueOf(matcher.group(1)));
				return getSection(item, tail);
			} else {
				for (int i = 0; i < jsonArray.length(); i++) {
					Object item = jsonArray.get(i);
					Object result = getSection(item, sectionPath);
					if (result != null) {
						return result;
					}
				}
			}
		}
		return null;
	}

	public static String firstNonBlank(Object... values) {
		for (Object value : values) {
			if ((value != null)) {
				return value.toString();
			}
		}
		return null;
	}

	public static String optString(JSONObject object, String key) {
		return object != null ? object.optString(key, "") : "";
	}

	private static Double normalize(String s) {
		return Double.parseDouble(s.replace("\u20AC", "").replace("+ ", "").replace(" x ", "").replace(",", ""));
	}

}
