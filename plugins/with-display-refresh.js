const {withMainActivity} = require('expo/config-plugins');

const marker = '// Whumpo display refresh preference';
const activityMembers = `
  ${marker}
  private val displayPower by lazy {
    getSystemService(android.os.PowerManager::class.java)
  }

  private val displayThermalListener = android.os.PowerManager.OnThermalStatusChangedListener {
    updateDisplayRefreshPreference()
  }

  private val displayPowerReceiver = object : android.content.BroadcastReceiver() {
    override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
      updateDisplayRefreshPreference()
    }
  }

  override fun onStart() {
    super.onStart()
    registerReceiver(
      displayPowerReceiver,
      android.content.IntentFilter(android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
    )
    displayPower.addThermalStatusListener(mainExecutor, displayThermalListener)
    updateDisplayRefreshPreference()
  }

  override fun onStop() {
    displayPower.removeThermalStatusListener(displayThermalListener)
    unregisterReceiver(displayPowerReceiver)
    super.onStop()
  }

  private fun updateDisplayRefreshPreference() {
    val panel = display ?: return
    val mode = panel.mode
    val rates = panel.supportedModes.filter {
      it.physicalWidth == mode.physicalWidth && it.physicalHeight == mode.physicalHeight
    }.map { it.refreshRate }
    val conserve = displayPower.isPowerSaveMode ||
      displayPower.currentThermalStatus >= android.os.PowerManager.THERMAL_STATUS_MODERATE
    val preferred = (if (conserve) rates.filter { it <= 60.5f } else rates).maxOrNull() ?: 0f
    val attributes = window.attributes
    if (attributes.preferredRefreshRate != preferred) {
      attributes.preferredRefreshRate = preferred
      window.attributes = attributes
    }
  }
`;

module.exports = function withDisplayRefresh(config) {
  return withMainActivity(config, config => {
    const source = config.modResults;
    if (source.language !== 'kt') throw new Error('Whumpo display preference requires Kotlin MainActivity.');
    if (source.contents.includes(marker)) return config;
    const anchor = 'class MainActivity : ReactActivity() {';
    if (!source.contents.includes(anchor) || /override fun on(Start|Stop)\(/.test(source.contents)) {
      throw new Error('Review the MainActivity lifecycle before applying Whumpo display preferences.');
    }
    source.contents = source.contents.replace(anchor, anchor + '\n' + activityMembers);
    return config;
  });
};
