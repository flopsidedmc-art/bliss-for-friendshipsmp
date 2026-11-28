package net.friendshipsmp.energygames;

import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Optional, reflection-only bridge to Skript so the plugin can keep talking to the
 * rest of the Bliss scripts (variables like {DragonGames}, functions like getEnergyToken()).
 * Does nothing when Skript isn't installed.
 */
final class SkriptBridge {
    private final Logger logger;
    private boolean initialised;
    private boolean available;
    private Method getVariable;
    private Method setVariable;
    private Method getFunction;
    private Method execute;
    private final Set<String> warned = new HashSet<>();

    SkriptBridge(Logger logger) {
        this.logger = logger;
    }

    private boolean init() {
        if (initialised) return available;
        initialised = true;
        Plugin skript = Bukkit.getPluginManager().getPlugin("Skript");
        if (skript == null || !skript.isEnabled()) return false;
        try {
            ClassLoader cl = skript.getClass().getClassLoader();
            Class<?> variables = Class.forName("ch.njol.skript.variables.Variables", true, cl);
            getVariable = variables.getMethod("getVariable", String.class, Event.class, boolean.class);
            setVariable = variables.getMethod("setVariable", String.class, Object.class, Event.class, boolean.class);

            Class<?> functions = Class.forName("ch.njol.skript.lang.function.Functions", true, cl);
            for (String name : new String[]{"getGlobalFunction", "getFunction"}) {
                try {
                    getFunction = functions.getMethod(name, String.class);
                    break;
                } catch (NoSuchMethodException ignored) {
                }
            }
            Class<?> function = Class.forName("ch.njol.skript.lang.function.Function", true, cl);
            execute = function.getMethod("execute", Object[][].class);
            available = true;
            logger.info("Hooked into Skript.");
        } catch (Throwable t) {
            logger.log(Level.WARNING, "Skript found but the hook failed; continuing without it.", t);
        }
        return available;
    }

    boolean isAvailable() {
        return init();
    }

    Object getVariable(String name) {
        if (!init()) return null;
        try {
            return getVariable.invoke(null, name, null, false);
        } catch (Throwable t) {
            warnOnce("get:" + name, "Could not read Skript variable {" + name + "}", t);
            return null;
        }
    }

    /** Sets a global Skript variable; a null value deletes it. */
    void setVariable(String name, Object value) {
        if (!init()) return;
        try {
            setVariable.invoke(null, name, value, null, false);
        } catch (Throwable t) {
            warnOnce("set:" + name, "Could not write Skript variable {" + name + "}", t);
        }
    }

    boolean hasFunction(String name) {
        if (!init() || getFunction == null) return false;
        try {
            return getFunction.invoke(null, name) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Calls a global Skript function. Returns its first return value, or null. */
    Object callFunction(String name, Object... args) {
        if (!init() || getFunction == null) return null;
        try {
            Object fn = getFunction.invoke(null, name);
            if (fn == null) return null;
            Object[][] params = new Object[args.length][];
            for (int i = 0; i < args.length; i++) params[i] = new Object[]{args[i]};
            Object result = execute.invoke(fn, (Object) params);
            if (result instanceof Object[] arr && arr.length > 0) return arr[0];
            return null;
        } catch (Throwable t) {
            warnOnce("fn:" + name, "Skript function " + name + "() failed", t);
            return null;
        }
    }

    /** Turns a Skript item/item type value into an ItemStack. */
    static ItemStack toItemStack(Object value) {
        if (value instanceof ItemStack stack) return stack.clone();
        if (value == null) return null;
        try {
            Method random = value.getClass().getMethod("getRandom"); // ch.njol.skript.aliases.ItemType
            Object stack = random.invoke(value);
            if (stack instanceof ItemStack s) return s.clone();
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void warnOnce(String key, String msg, Throwable t) {
        if (warned.add(key)) logger.log(Level.WARNING, msg + " (further errors hidden)", t);
    }
}
