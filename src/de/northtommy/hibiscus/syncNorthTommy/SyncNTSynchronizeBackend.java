package de.northtommy.hibiscus.syncNorthTommy;

import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Resource;

import de.northtommy.hibiscus.syncNorthTommy.SyncNTSynchronizeJobProvider;
import de.northtommy.hibiscus.syncNorthTommy.SyncNTSynchronizeJob;
import de.willuhn.annotation.Lifecycle;
import de.willuhn.annotation.Lifecycle.Type;
import de.willuhn.jameica.hbci.rmi.Konto;
import de.willuhn.jameica.hbci.synchronize.AbstractSynchronizeBackend;
import de.willuhn.jameica.hbci.synchronize.SynchronizeBackend;
import de.willuhn.jameica.hbci.synchronize.SynchronizeEngine;
import de.willuhn.jameica.hbci.synchronize.SynchronizeJobProvider;
import de.willuhn.util.ProgressMonitor;

public abstract class SyncNTSynchronizeBackend extends AbstractSynchronizeBackend 
{
	@Resource
	private SynchronizeEngine engine = null;

	@Override
	protected JobGroup createJobGroup(Konto k) {
		return new SyncNTJobGroup(k);
	}


	@Override
	protected Class<? extends SynchronizeJobProvider> getJobProviderInterface() {
		return SyncNTSynchronizeJobProvider.class;
	}

	/**
	 * @see de.willuhn.jameica.hbci.synchronize.AbstractSynchronizeBackend#getPropertyNames(de.willuhn.jameica.hbci.rmi.Konto)
	 */
	@Override
	public List<String> getPropertyNames(Konto konto)
	{
    	return null;
	}

	@Override
	public List<Konto> getSynchronizeKonten(Konto k)
	{
		List<Konto> list = super.getSynchronizeKonten(k);
		List<Konto> result = new ArrayList<Konto>();

		// Wir wollen nur die Offline-Konten und jene, bei denen Scripting explizit konfiguriert ist
		for (Konto konto:list)
		{
			if (konto != null)
			{
				SynchronizeBackend backend = engine.getBackend(konto);
				if (backend != null && backend.equals(this))
				{
					result.add(konto);
				}
			}
		}

		return result;
	}

	protected class SyncNTJobGroup extends JobGroup
	{
		protected SyncNTJobGroup(Konto k) {
			super(k);
		}

		@Override
		protected void sync() throws Exception
		{
			////////////////////////////////////////////////////////////////////
			// lokale Variablen
			ProgressMonitor monitor = worker.getMonitor();

			////////////////////////////////////////////////////////////////////

			this.checkInterrupted();

			for (Object job:this.jobs)
			{
				this.checkInterrupted();

				SyncNTSynchronizeJob j = (SyncNTSynchronizeJob) job;
				j.execute(monitor);
			}
		}
	}
}
