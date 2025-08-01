package de.northtommy.hibiscus.syncNorthTommy.traderepublic;

import java.rmi.RemoteException;
import java.util.ArrayList;
import javax.annotation.Resource;

import de.northtommy.hibiscus.syncNorthTommy.SyncNTSynchronizeJobProviderKontoauszug;
import de.willuhn.jameica.hbci.rmi.Konto;
import de.willuhn.jameica.hbci.synchronize.AbstractSynchronizeBackend;
import de.willuhn.jameica.hbci.synchronize.jobs.SynchronizeJob;

public class TraderepublicSynchronizeJobProviderKontoauszug extends SyncNTSynchronizeJobProviderKontoauszug 
{
	@Resource
	private TraderepublicSynchronizeBackend backend = null;

	@Override
	protected AbstractSynchronizeBackend<?> getBackend() { return backend; }

	public TraderepublicSynchronizeJobProviderKontoauszug()
	{
		JOBS = new ArrayList<Class<? extends SynchronizeJob>>()
		{{
			add(TraderepublicSynchronizeJobKontoauszug.class);
		}};
	}

	@Override
	public boolean supports(Class type, Konto k) 
	{
		try
		{
			return k.getBLZ().equals("10012345");
		} 
		catch (RemoteException e) 
		{
			return false;
		}
	}
}
