package de.northtommy.hibiscus.syncNorthTommy.amzzinia;

import java.rmi.RemoteException;
import java.util.ArrayList;
import javax.annotation.Resource;

import de.northtommy.hibiscus.syncNorthTommy.SyncNTSynchronizeJobProviderKontoauszug;
import de.willuhn.jameica.hbci.rmi.Konto;
import de.willuhn.jameica.hbci.synchronize.AbstractSynchronizeBackend;
import de.willuhn.jameica.hbci.synchronize.jobs.SynchronizeJob;

public class AmzZiniaSynchronizeJobProviderKontoauszug extends SyncNTSynchronizeJobProviderKontoauszug 
{
	@Resource
	private AmzZiniaSynchronizeBackend backend = null;

	@Override
	protected AbstractSynchronizeBackend<?> getBackend() { return backend; }

	public AmzZiniaSynchronizeJobProviderKontoauszug()
	{
		JOBS = new ArrayList<Class<? extends SynchronizeJob>>()
		{{
			add(AmzZiniaSynchronizeJobKontoauszug.class);
		}};
	}

	@Override
	public boolean supports(Class type, Konto k) 
	{
		try
		{
			return k.getBLZ().equals("50034200");
		} 
		catch (RemoteException e) 
		{
			return false;
		}
	}
}
