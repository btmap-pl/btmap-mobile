package pl.btmap.mobile

import android.app.Application
import pl.btmap.core.BtmapCore

class BtmapApp : Application() {
    val core: BtmapCore by lazy { BtmapCore(this, BuildConfig.API_BASE_URL) }
}
