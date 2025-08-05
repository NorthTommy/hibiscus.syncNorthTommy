package de.northtommy.hibiscus.syncNorthTommy.traderepublic;

import de.northtommy.hibiscus.syncNorthTommy.SyncNTSynchronizeBackend;
import de.willuhn.annotation.Lifecycle;
import de.willuhn.annotation.Lifecycle.Type;

@Lifecycle(Type.CONTEXT)
public class TraderepublicSynchronizeBackend extends SyncNTSynchronizeBackend
{
    @Override
    public String getName()
    {
        return "Traderepublic";
    }
}
