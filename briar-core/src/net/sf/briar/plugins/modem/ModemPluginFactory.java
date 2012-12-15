package net.sf.briar.plugins.modem;

import java.util.concurrent.Executor;

import net.sf.briar.api.plugins.PluginExecutor;
import net.sf.briar.api.plugins.duplex.DuplexPlugin;
import net.sf.briar.api.plugins.duplex.DuplexPluginCallback;
import net.sf.briar.api.plugins.duplex.DuplexPluginFactory;
import net.sf.briar.api.protocol.TransportId;

import org.h2.util.StringUtils;

public class ModemPluginFactory implements DuplexPluginFactory {

	private static final long POLLING_INTERVAL = 60L * 60L * 1000L; // 1 hour

	private final Executor pluginExecutor;

	public ModemPluginFactory(@PluginExecutor Executor pluginExecutor) {
		this.pluginExecutor = pluginExecutor;
	}

	public TransportId getId() {
		return ModemPlugin.ID;
	}

	public DuplexPlugin createPlugin(DuplexPluginCallback callback) {
		// This plugin is not enabled by default
		String enabled = callback.getConfig().get("enabled");
		if(StringUtils.isNullOrEmpty(enabled)) return null;
		ReliabilityLayerFactory reliabilityFactory =
				new ReliabilityLayerFactoryImpl(pluginExecutor);
		ModemFactory modemFactory = new ModemFactoryImpl(pluginExecutor,
				reliabilityFactory);
		return new ModemPlugin(pluginExecutor, modemFactory, callback,
				POLLING_INTERVAL);
	}
}
