package de.northtommy.hibiscus.syncNorthTommy;

import de.willuhn.logging.Level;

public interface SyncNTSynchronizeJobKontoauszugLoggerI {
	public void log(Level level, String msg);
	public void incrementPercentComplete(int arg0);
	public void updatePercentComplete(int arg0);
}
