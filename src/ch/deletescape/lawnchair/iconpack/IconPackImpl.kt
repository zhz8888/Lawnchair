package ch.deletescape.lawnchair.iconpack

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherActivityInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Looper
import android.util.Log
import android.util.Xml
import android.widget.Toast
import com.android.launcher3.*
import com.android.launcher3.compat.LauncherAppsCompat
import com.android.launcher3.compat.UserManagerCompat
import com.android.launcher3.shortcuts.DeepShortcutManager
import com.android.launcher3.util.ComponentKey
import com.google.android.apps.nexuslauncher.CustomIconUtils
import com.google.android.apps.nexuslauncher.clock.CustomClock
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory
import java.io.IOException
import java.util.*
import kotlin.collections.ArrayList

class IconPackImpl(context: Context, packPackageName: String) : IconPack(context, packPackageName) {

    private val TAG = "IconPackImpl"

    private val packComponents: MutableMap<ComponentName, Entry> = HashMap()
    private val packCalendars: MutableMap<ComponentName, String> = HashMap()
    private val packClocks: MutableMap<Int, CustomClock.Metadata> = HashMap()
    private val packResources = context.packageManager.getResourcesForApplication(packPackageName)
    override val entries get() = packComponents.values.toList()

    init {
        Log.d(TAG, "init pack $packPackageName on ${Looper.myLooper().thread.name}")
        executeLoadPack()
    }

    private val applicationInfo by lazy { context.packageManager.getApplicationInfo(packPackageName, PackageManager.GET_META_DATA) }

    override val displayIcon by lazy {
        context.packageManager.getApplicationIcon(applicationInfo)
    }

    override val displayName by lazy {
        context.packageManager.getApplicationLabel(applicationInfo) as String
    }

    override fun onDateChanged() {
        val apps = LauncherAppsCompat.getInstance(context)
        val model = LauncherAppState.getInstance(context).model
        val shortcutManager = DeepShortcutManager.getInstance(context)
        for (user in UserManagerCompat.getInstance(context).userProfiles) {
            packCalendars.keys.forEach {
                val pkg = it.packageName
                if (!apps.getActivityList(pkg, user).isEmpty()) {
                    CustomIconUtils.reloadIcon(shortcutManager, model, user, pkg)
                }
            }
        }
    }

    override fun loadPack() {
        try {
            val startTime = System.currentTimeMillis()
            val pm = context.packageManager
            val res = packResources
            val resId = res.getIdentifier("appfilter", "xml", packPackageName)
            if (resId != 0) {
                val compStart = "ComponentInfo{"
                val compStartlength = compStart.length
                val compEnd = "}"
                val compEndLength = compEnd.length

                val parseXml = pm.getXml(packPackageName, resId, null)
                while (parseXml.next() != XmlPullParser.END_DOCUMENT) {
                    if (parseXml.eventType == XmlPullParser.START_TAG) {
                        val name = parseXml.name
                        val isCalendar = name == "calendar"
                        if (isCalendar || name == "item") {
                            var componentName: String? = parseXml.getAttributeValue(null, "component")
                            val drawableName = parseXml.getAttributeValue(null, if (isCalendar) "prefix" else "drawable")
                            if (componentName != null && drawableName != null && componentName.startsWith(compStart) && componentName.endsWith(compEnd)) {
                                componentName = componentName.substring(compStartlength, componentName.length - compEndLength)
                                val parsed = ComponentName.unflattenFromString(componentName)
                                if (parsed != null) {
                                    if (isCalendar) {
                                        packCalendars[parsed] = drawableName
                                    } else {

                                        val drawableId = res.getIdentifier(drawableName, "drawable", packPackageName)
                                        if (drawableId != 0) {
                                            packComponents[parsed] = Entry(drawableName, drawableId)
                                        }
                                    }
                                }
                            }
                        } else if (name == "dynamic-clock") {
                            val drawableName = parseXml.getAttributeValue(null, "drawable")
                            if (drawableName != null) {
                                val drawableId = res.getIdentifier(drawableName, "drawable", packPackageName)
                                if (drawableId != 0) {
                                    packClocks[drawableId] = CustomClock.Metadata(
                                            parseXml.getAttributeIntValue(null, "hourLayerIndex", -1),
                                            parseXml.getAttributeIntValue(null, "minuteLayerIndex", -1),
                                            parseXml.getAttributeIntValue(null, "secondLayerIndex", -1),
                                            parseXml.getAttributeIntValue(null, "defaultHour", 0),
                                            parseXml.getAttributeIntValue(null, "defaultMinute", 0),
                                            parseXml.getAttributeIntValue(null, "defaultSecond", 0))
                                }
                            }
                        }
                    }
                }
            }
            val endTime = System.currentTimeMillis()
            Log.d("IconPackImpl", "completed parsing pack $packPackageName in ${endTime - startTime}ms")
        } catch (e: PackageManager.NameNotFoundException) {
            e.printStackTrace()
        } catch (e: XmlPullParserException) {
            e.printStackTrace()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    override fun getEntryForComponent(key: ComponentKey) = packComponents[key.componentName]

    override fun getIcon(launcherActivityInfo: LauncherActivityInfo, iconDpi: Int,
                         flattenDrawable: Boolean, customIconEntry: IconPackManager.CustomIconEntry?,
                         basePack: IconPack, iconProvider: LawnchairIconProvider?): Drawable {
        ensureInitialLoadComplete()

        val component = launcherActivityInfo.componentName
        var drawableId = 0
        if (customIconEntry?.icon != null) {
            drawableId = getDrawableId(customIconEntry.icon)
        } else if (packCalendars.containsKey(component)) {
            drawableId = getDrawableId(packCalendars[component] + Calendar.getInstance().get(Calendar.DAY_OF_MONTH))
        } else if (packComponents.containsKey(component)) {
            val entry = packComponents[component]!!
            drawableId = entry.drawableId
        }

        if (drawableId != 0) {
            try {
                var drawable = packResources.getDrawable(drawableId)
                if (Utilities.ATLEAST_OREO && packClocks.containsKey(drawableId)) {
                    drawable = CustomClock.getClock(context, drawable, packClocks[drawableId], iconDpi)
                }
                return drawable.mutate()
            } catch (e: Resources.NotFoundException) {
                Log.e(TAG, "Can't get drawable for $component ($drawableId)", e)
            }
        }

        return basePack.getIcon(launcherActivityInfo, iconDpi,
                flattenDrawable, null, IconPackManager.getInstance(context).defaultPack, iconProvider)
    }

    override fun newIcon(icon: Bitmap, itemInfo: ItemInfo, customIconEntry: IconPackManager.CustomIconEntry?,
                         basePack: IconPack, drawableFactory: LawnchairDrawableFactory): FastBitmapDrawable {
        ensureInitialLoadComplete()

        if (Utilities.ATLEAST_OREO && itemInfo.itemType == LauncherSettings.Favorites.ITEM_TYPE_APPLICATION) {
            val component = itemInfo.targetComponent
            var drawableId = 0
            if (customIconEntry?.icon != null) {
                drawableId = getDrawableId(customIconEntry.icon)
            } else if (packComponents.containsKey(component)) {
                drawableId = packComponents[component]!!.drawableId
            }
            if (packClocks.containsKey(drawableId)) {
                val drawable = packResources.getDrawable(drawableId)
                return drawableFactory.customClockDrawer.drawIcon(icon, drawable, packClocks[drawableId])
            } else if (drawableId != 0) {
                return FastBitmapDrawable(icon)
            }
        }
        return basePack.newIcon(icon, itemInfo, null,
                IconPackManager.getInstance(context).defaultPack, drawableFactory)
    }

    override fun getAllIcons(callback: (List<PackEntry>) -> Unit, cancel: () -> Boolean) {
        var lastSend = System.currentTimeMillis() - 900
        var tmpList = ArrayList<PackEntry>()
        val sendResults = {
            val current = System.currentTimeMillis()
            if (current - lastSend >= 1000) {
                callback(tmpList)
                tmpList = ArrayList()
                lastSend = current
            }
        }
        var found = false
        val startTime = System.currentTimeMillis()
        val res = packResources
        val xmlRes = res.getIdentifier("appfilter", "xml", packPackageName)
        if (xmlRes != 0) {
            var entry: Entry
            try {
                val parser = getXml("drawable")
                Log.d("IconPackImpl", "initialized parser for pack $packPackageName in ${System.currentTimeMillis() - startTime}ms")
                while (parser != null && parser.next() != XmlPullParser.END_DOCUMENT) {
                    if (cancel()) return
                    if (parser.eventType != XmlPullParser.START_TAG) continue
                    if ("category" == parser.name) {
                        val title = parser.getAttributeValue(null, "title")
                        tmpList.add(CategoryTitle(title))
                        sendResults()
                    } else if ("item" == parser.name) {
                        val drawableName = parser.getAttributeValue(null, "drawable")
                        val resId = Utilities.parseResourceIdentifier(packResources, "@drawable/$drawableName", packPackageName)
                        if (resId != 0) {
                            entry = Entry(drawableName, resId)
                            tmpList.add(entry)
                            sendResults()
                            found = true
                        }
                    }
                }
                if (found) {
                    return
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        super.getAllIcons(callback, cancel)
    }

    private fun getXml(name: String): XmlPullParser? {
        val res: Resources
        try {
            res = context.packageManager.getResourcesForApplication(packPackageName)
            val resourceId = res.getIdentifier(name, "xml", packPackageName)
            return if (0 != resourceId) {
                context.packageManager.getXml(packPackageName, resourceId, null)
            } else {
                val factory = XmlPullParserFactory.newInstance()
                val parser = factory.newPullParser()
                parser.setInput(res.assets.open("$name.xml"), Xml.Encoding.UTF_8.toString())
                parser
            }
        } catch (e: PackageManager.NameNotFoundException) {
            Toast.makeText(context, "Failed to get AppFilter", Toast.LENGTH_SHORT).show()
        } catch (e: IOException) {
            Toast.makeText(context, "Failed to get AppFilter", Toast.LENGTH_SHORT).show()
        } catch (e: XmlPullParserException) {
            Toast.makeText(context, "Failed to get AppFilter", Toast.LENGTH_SHORT).show()
        }

        return null
    }

    fun getDrawable(name: String, density: Int): Drawable? {
        val id = getDrawableId(name)
        return try {
            if (id != 0) packResources.getDrawableForDensity(id, density) else null
        } catch (e: Resources.NotFoundException) {
            Log.e(TAG, "Can't get drawable $id($name) from $packPackageName", e)
            null
        }
    }

    private fun getDrawableId(name: String) = packResources.getIdentifier(name, "drawable", packPackageName)

    fun createEntry(icon: Intent.ShortcutIconResource): Entry {
        val id = packResources.getIdentifier(icon.resourceName, null, null)
        val simpleName = packResources.getResourceEntryName(id)
        return Entry(simpleName, id)
    }

    inner class Entry(private val drawableName: String, val drawableId: Int) : IconPack.Entry() {

        override val displayName = drawableName
        override val identifierName = drawableName
        override val drawable: Drawable
            get() {
                try {
                    return packResources.getDrawable(drawableId)
                } catch (e: Resources.NotFoundException) {
                    throw Exception("Failed to get drawable $drawableId ($drawableName) from $packPackageName", e)
                }
            }

        override fun toCustomEntry() = IconPackManager.CustomIconEntry(packPackageName, drawableName)
    }

}