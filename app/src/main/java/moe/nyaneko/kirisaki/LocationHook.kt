// SPDX-License-Identifier: GPL-3.0-only

package moe.nyaneko.kirisaki

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Bundle
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Runs exclusively inside the requested LSPosed scope. No service, networking, or polling. */
class LocationHook : IXposedHookLoadPackage {
    override fun handleLoadPackage(param: XC_LoadPackage.LoadPackageParam) {
        // The enforcement service runs in the main location process. Privacy/settings
        // subprocesses must not overwrite its authoritative state or receive extra hooks.
        if (param.packageName != TARGET_PACKAGE || param.processName != TARGET_PACKAGE) return
        XposedBridge.log("Kirisaki: module entry ${param.processName} v${BuildConfig.VERSION_NAME}")
        runCatching {
            android.app.AndroidAppHelper.currentApplication()?.let { start(it, param.classLoader) }
        }
        // All callbacks contain their own failure boundary; never throw into the host app.
        runCatching {
            XposedBridge.hookAllMethods(Application::class.java, "attach", object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    runCatching { start(p.args[0] as Context, param.classLoader) }
                        .onFailure { XposedBridge.log("Kirisaki: initialization failed: ${it.javaClass.simpleName}") }
                }
            })
        }.onFailure { XposedBridge.log("Kirisaki: attach hook unavailable") }
    }

    private var started = false
    private lateinit var context: Context
    private lateinit var loader: ClassLoader
    private var sinkHooks = emptySet<XC_MethodHook.Unhook>()
    @Volatile private var precise: Method? = null
    @Volatile private var otherEntryBlocked = false
    @Volatile private var error = ""
    @Volatile private var blocked = 0L
    @Volatile private var stateReply: android.os.ResultReceiver? = null
    private var lastReport = 0L
    // IPC and persistence must never block the location process's main thread.
    // The worker expires after five idle seconds; only the latest pending snapshot is kept.
    private val reporter = ThreadPoolExecutor(0, 1, 5, TimeUnit.SECONDS,
        ArrayBlockingQueue<Runnable>(1),
        { task -> Thread(task, "Kirisaki-state").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardOldestPolicy())
    private val targetDepth = ThreadLocal.withInitial { 0 }

    // API 31/32 have no exported receiver flag; newer releases use the explicit flag below.
    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Synchronized private fun start(ctx: Context, classLoader: ClassLoader) {
        if (started) return
        started = true
        XposedBridge.log("Kirisaki: installing guard")
        context = ctx
        loader = classLoader
        runCatching { sinkHooks = installSink() }
            .onFailure { error = "ContentResolver hook: ${it.javaClass.simpleName}" }
        runCatching {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.action == STATE_REQUEST_ACTION) runCatching {
                        @Suppress("DEPRECATION")
                        val reply = intent.getParcelableExtra<android.os.ResultReceiver>("reply") ?: return@runCatching
                        stateReply = reply
                        report(true)
                    }
                }
            }
            // Permission is checked by Android against the broadcast sender's actual UID.
            // Only Kirisaki's signer can request a fresh snapshot; no payload is accepted.
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, IntentFilter(STATE_REQUEST_ACTION),
                    STATE_REQUEST_PERMISSION, null, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, IntentFilter(STATE_REQUEST_ACTION), STATE_REQUEST_PERMISSION, null)
            }
        }.onFailure { XposedBridge.log("Kirisaki: state request registration failed"); XposedBridge.log(it) }
        report(true)
    }

    private fun installSink(): Set<XC_MethodHook.Unhook> =
        XposedBridge.hookAllMethods(ContentResolver::class.java, "update", object : XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) {
                // Separate matching from observation: a reporting or discovery failure must not undo a block.
                val matches = runCatching {
                    val uri = p.args.getOrNull(0) as? Uri
                    val values = p.args.getOrNull(1) as? ContentValues
                    values != null && WritePolicy.blocks(uri?.toString(), values.getAsString("name"),
                        values.getAsString("value"), values.keySet())
                }.getOrDefault(false)
                if (!matches) return
                p.result = 0
                runCatching {
                    blocked = System.currentTimeMillis()
                    val stack = Throwable().stackTrace
                    val target = precise
                    val newlyObservedEntry = target != null && (targetDepth.get() ?: 0) == 0 &&
                        EntryPolicy.isOtherEntry(target.declaringClass.name, target.name, stack)
                    val firstOtherEntry = newlyObservedEntry && !otherEntryBlocked
                    if (newlyObservedEntry) otherEntryBlocked = true
                    if (target == null) discover(stack)
                    // The write guard stays installed for every entry, even after target hook success.
                    // Report a new path immediately rather than dropping it under the IPC throttle.
                    report(firstOtherEntry || (target == null && precise != null))
                }.onFailure {
                    error = "Discovery failed; strict fallback active: ${it.javaClass.simpleName}"
                    report(false)
                }
            }
        }).also { check(it.isNotEmpty()) { "No update overloads found" } }

    @Synchronized private fun discover(stack: Array<StackTraceElement>) {
        if (precise != null) return
        var attempted = 0
        for (frame in stack) {
            if (frame.className.startsWith("android.") || frame.className.startsWith("java.") ||
                frame.className.startsWith("de.robv.") || frame.className.startsWith("moe.nyaneko.kirisaki.")) continue
            val clazz = runCatching { Class.forName(frame.className, false, loader) }.getOrNull() ?: continue
            if (clazz.classLoader != loader) continue
            val methods = runCatching { clazz.declaredMethods.filter { it.name == frame.methodName } }.getOrNull() ?: continue
            val method = methods.singleOrNull() ?: continue
            if (method.returnType != Void.TYPE || !method.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType)) ||
                method.isSynthetic || Modifier.isAbstract(method.modifiers) || Modifier.isNative(method.modifiers)) continue
            if (++attempted > 8) break
            // Install on this first matched write. Signature checks identify a candidate, not
            // proof of its entire purpose: never skip its body or alter its arguments/results.
            val installed = runCatching {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        runCatching { targetDepth.set((targetDepth.get() ?: 0) + 1) }
                    }
                    override fun afterHookedMethod(p: MethodHookParam) {
                        runCatching {
                            val depth = (targetDepth.get() ?: 1) - 1
                            if (depth <= 0) targetDepth.remove() else targetDepth.set(depth)
                        }
                    }
                })
                precise = method
                error = ""
                XposedBridge.log("Kirisaki: target hook installed; write guard remains active: ${method.declaringClass.name}.${method.name}")
            }.onFailure {
                error = "Target hook failed; write guard remains active: ${it.javaClass.simpleName}"
            }.isSuccess
            if (installed) return
        }
    }

    @Synchronized private fun report(force: Boolean) {
        // Bound IPC traffic. Failed calls are retried on the next real host event.
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && now - lastReport < 1000) return
        lastReport = now
        val reply = stateReply ?: return
        val snapshot = Bundle().apply {
            putBoolean("precise", precise != null)
            putBoolean("other_entry", otherEntryBlocked)
            putString("class", precise?.declaringClass?.name.orEmpty())
            putString("method", precise?.let { "${it.name}(boolean): void" }.orEmpty())
            putString("error", error)
            putLong("blocked", blocked)
        }
        runCatching {
            reporter.execute {
                runCatching {
                    snapshot.putString("boot", BootSession.id(context))
                    reply.send(0, snapshot)
                }.onFailure { XposedBridge.log("Kirisaki: private state unavailable: ${it.message}"); XposedBridge.log(it) }
            }
        }.onFailure { XposedBridge.log("Kirisaki: state reporter unavailable") }
    }
}
