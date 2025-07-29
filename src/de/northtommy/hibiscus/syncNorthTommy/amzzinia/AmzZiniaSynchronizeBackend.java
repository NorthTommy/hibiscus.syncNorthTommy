package de.northtommy.hibiscus.syncNorthTommy.amzzinia;

import de.northtommy.hibiscus.syncNorthTommy.SyncNTSynchronizeBackend;
import de.willuhn.annotation.Lifecycle;
import de.willuhn.annotation.Lifecycle.Type;

@Lifecycle(Type.CONTEXT)
public class AmzZiniaSynchronizeBackend extends SyncNTSynchronizeBackend
{
    @Override
    public String getName()
    {
        return "Amazon VISA (Zinia)";
    }
}
