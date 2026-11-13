import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../phone/phone_channel.dart';
import '../l10n/l10n.dart';
import '../runtime/runtime_bridge.dart';
import '../runtime/runtime_state.dart';
import 'engine_control.dart';
import 'hosts_state.dart';

export '../phone/phone_channel.dart';

final phoneChannelProvider = Provider<PhoneChannel>((ref) => PhoneChannel());

/// What the phone-control page shows: native status, the app list, the log.
class PhoneState {
  const PhoneState({
    this.status = const PhoneStatus.notReady(),
    this.loaded = false,
    this.policies,
    this.retained = const [],
    this.log = const [],
    this.toggling = false,
    this.disclosureAccepted = false,
  });

  final PhoneStatus status;

  /// False until the first `status` reply (ready or not).
  final bool loaded;

  /// Null until read, or when the native side doesn't answer.
  final PhonePolicies? policies;

  /// Packages the person changed this session: they stay listed after leaving
  /// both lists, so a change can be undone without typing the package again.
  final List<String> retained;

  /// Newest first.
  final List<PhoneLogEntry> log;

  /// The master switch is being flipped.
  final bool toggling;

  /// The 显著披露 was accepted once (persisted).
  final bool disclosureAccepted;

  PhoneState copyWith({
    PhoneStatus? status,
    bool? loaded,
    PhonePolicies? policies,
    List<String>? retained,
    List<PhoneLogEntry>? log,
    bool? toggling,
    bool? disclosureAccepted,
  }) => PhoneState(
    status: status ?? this.status,
    loaded: loaded ?? this.loaded,
    policies: policies ?? this.policies,
    retained: retained ?? this.retained,
    log: log ?? this.log,
    toggling: toggling ?? this.toggling,
    disclosureAccepted: disclosureAccepted ?? this.disclosureAccepted,
  );
}

/// Outcome of flipping the master switch.
class PhoneToggleResult {
  const PhoneToggleResult({required this.ok, this.restarting = false});

  final bool ok;

  /// The local engine is restarting so the plugin mounts or unmounts.
  final bool restarting;
}

class PhoneController extends Notifier<PhoneState> {
  static const disclosureKey = 'phone_disclosure_v1';

  /// First run's 关键步骤先问我 / 全程自动 until the native side has it.
  static const confirmPendingKey = 'phone_confirm_pending';

  StreamSubscription<PhoneStatus>? _sub;
  Future<bool>? _applying;
  int? _statusRevision;

  PhoneChannel get _channel => ref.read(phoneChannelProvider);

  @override
  PhoneState build() {
    ref.onDispose(() => _sub?.cancel());
    unawaited(_loadDisclosure());
    unawaited(refresh());
    return const PhoneState();
  }

  Future<void> _loadDisclosure() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final accepted = prefs.getBool(disclosureKey) ?? false;
      if (ref.mounted && accepted) {
        state = state.copyWith(disclosureAccepted: true);
      }
    } catch (_) {
      // No preferences backend: ask again next time, which is the safe side.
    }
  }

  /// Status, app list and recent log, all at once.
  Future<bool> refresh() async {
    final ready = await refreshStatus();
    if (!ref.mounted || !ready) return false;
    await Future.wait([refreshPolicies(), refreshLog()]);
    return ready;
  }

  /// True only when this read supplies an accepted, ready native snapshot.
  Future<bool> refreshStatus() async {
    final revisionAtRequest = _statusRevision;
    final status = await _channel.status();
    if (!ref.mounted) return false;
    if (!status.ready) {
      if (_statusRevision != revisionAtRequest) return false;
      if (_statusRevision != null) {
        // A transport failure is not evidence that the last task ended.
        state = state.copyWith(status: state.status.copyWith(ready: false, error: status.error), loaded: true);
      } else {
        _apply(status);
      }
      return false;
    }
    final accepted = _apply(status);
    if (accepted) {
      // Only now: activating the event stream with no native side reports an error.
      _sub ??= _channel.statusChanges.listen(_apply, onError: (_) {});
    }
    return accepted;
  }

  bool _apply(PhoneStatus status) {
    if (!ref.mounted) return false;
    final revision = status.statusRevision;
    if (status.statusRevisionPresent && revision == null) return false;
    final previous = _statusRevision;
    if (previous != null && (revision == null || revision <= previous)) return false;
    if (revision != null) _statusRevision = revision;
    final taskEnded = state.status.task.active && !status.task.active;
    state = state.copyWith(status: status, loaded: true);
    if (taskEnded) unawaited(refreshLog());
    return true;
  }

  Future<void> refreshPolicies() async {
    final policies = await _channel.getPolicies();
    if (!ref.mounted) return;
    if (policies == null) {
      final current = state.policies;
      if (current != null) {
        state = state.copyWith(policies: current.withLocalOcrEnabled(null));
      }
      return;
    }
    state = state.copyWith(policies: policies);
    // A first-run choice the native side didn't take yet goes in now.
    if (await _pendingConfirmMode() != null && ref.mounted) {
      unawaited(applyPendingConfirmMode());
    }
  }

  Future<void> refreshLog({int limit = 50}) async {
    final log = await _channel.actionLog(limit: limit);
    if (!ref.mounted) return;
    final sorted = [...log];
    if (sorted.every((e) => e.time != null)) {
      sorted.sort((a, b) => b.time!.compareTo(a.time!));
    }
    state = state.copyWith(log: List.unmodifiable(sorted));
  }

  Future<void> acceptDisclosure() async {
    state = state.copyWith(disclosureAccepted: true);
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setBool(disclosureKey, true);
    } catch (_) {}
  }

  /// Flips 「手机操控」, then restarts a running local engine so the phone plugin
  /// mounts or unmounts (tools are fixed per DSH launch). In safe mode nothing
  /// loads plugins anyway: turning on is refused, turning off needs no restart.
  Future<PhoneToggleResult> setEnabled(bool enabled) async {
    if (state.toggling) return const PhoneToggleResult(ok: false);
    final runtime = ref.read(localRuntimeProvider).status;
    if (enabled && runtime.inSafeMode) {
      return const PhoneToggleResult(ok: false);
    }
    state = state.copyWith(toggling: true);
    var restarting = false;
    try {
      final ok = await _channel.setEnabled(enabled);
      if (!ref.mounted) return PhoneToggleResult(ok: ok);
      if (!ok) return const PhoneToggleResult(ok: false);
      state = state.copyWith(status: state.status.copyWith(enabled: enabled));
      final now = ref.read(localRuntimeProvider).status;
      if (!now.inSafeMode && (now.phase == RuntimePhase.running || now.phase == RuntimePhase.starting)) {
        restarting = true;
        unawaited(EngineControl.restart(ref.container));
      }
      unawaited(refreshStatus());
      return PhoneToggleResult(ok: true, restarting: restarting);
    } finally {
      if (ref.mounted) state = state.copyWith(toggling: false);
    }
  }

  Future<bool> openAccessibilitySettings() => _channel.openAccessibilitySettings();

  Future<PhoneResult> grantSecureSettings() => _thenRefresh(_channel.grantSecureSettings());

  Future<bool> openAppDetails() => _channel.openAppDetails();

  Future<ScreenTestResult> testScreen() => _channel.testScreen();

  Future<bool> stopTask({int? taskId}) async {
    final id = taskId ?? (state.status.taskProtocol == 1 ? state.status.task.id : null);
    if (state.status.taskProtocol == 1 && id == null) return false;
    final ok = await _channel.stopTask(taskId: id);
    unawaited(refreshStatus());
    return ok;
  }

  Future<bool> retryTaskCleanup(int taskId) async {
    final ok = await _channel.retryTaskCleanup(taskId);
    unawaited(refreshStatus());
    return ok;
  }

  Future<bool> acknowledgeTaskInterruption(String batchToken) async {
    final ok = await _channel.acknowledgeTaskInterruption(batchToken);
    await refreshStatus();
    return ok;
  }

  Future<bool> retryTaskInterruptionStorage() async {
    final ok = await _channel.retryTaskInterruptionStorage();
    await refreshStatus();
    return ok;
  }

  Future<bool> resumeTask(int taskId) async {
    final ok = await _channel.resumeTask(taskId);
    unawaited(refreshStatus());
    return ok;
  }

  Future<bool> pauseTask(int taskId, bool paused) async {
    final ok = await _channel.pauseTask(taskId, paused);
    unawaited(refreshStatus());
    return ok;
  }

  Future<PhoneResult> retryBackgroundRecovery() =>
      _thenRefresh(_channel.retryBackgroundRecovery());

  Future<PhoneResult> retryBackgroundGateCleanup(String handle) =>
      _thenRefresh(_channel.retryBackgroundGateCleanup(handle));

  Future<PhoneResult> setBackgroundMode(PhoneBackgroundMode mode) => _thenRefresh(_channel.setBackgroundMode(mode));

  Future<PhoneResult> setBackgroundViewport(PhoneBackgroundViewport preset) =>
      _thenRefresh(_channel.setBackgroundViewport(preset));

  Future<PhoneResult> setBackgroundParallelLimit(int limit) async {
    final result = await _channel.setBackgroundParallelLimit(limit);
    if (ref.mounted) await refreshStatus();
    return result;
  }

  Future<bool> setBackgroundPreview(bool enabled) => _thenRefresh(_channel.setBackgroundPreview(enabled));

  /// Refused before any channel call when the native side doesn't offer [mode].
  Future<PhoneResult> setPrivilegedMode(PhonePrivilegedMode mode) =>
      _ifSupported(mode, () => _channel.setPrivilegedMode(mode));

  /// Only an explicit keep choice; rebuilding or refreshing never writes it.
  Future<PhoneResult> setBackgroundKeep(PhonePrivilegedMode mode, bool enabled) =>
      _ifSupported(mode, () => _channel.setBackgroundKeep(mode, enabled));

  /// Only an explicit tap: refresh, build and status events never prepare root.
  Future<PhoneResult> prepareRoot() => _ifSupported(PhonePrivilegedMode.root, _channel.prepareRoot);

  Future<PhoneResult> _ifSupported(PhonePrivilegedMode mode, Future<PhoneResult> Function() action) =>
      state.status.background.supportedModes.contains(mode)
      ? _thenRefresh(action())
      : Future.value(PhoneResult(false, appL10n.phonePrivilegedUpgrade));

  /// The old Shizuku choice asks whichever provider serves the shared API now, Sui included.
  Future<bool> requestPrivilegedPermission(PhonePrivilegedMode mode) {
    final background = state.status.background;
    if (mode != PhonePrivilegedMode.shizuku && mode != PhonePrivilegedMode.sui) return Future.value(false);
    if (!background.supportedModes.contains(mode)) return Future.value(false);
    final provider = mode == PhonePrivilegedMode.sui ? 'sui' : background.shizuku.provider;
    return provider == null ? requestShizukuPermission() : _thenRefresh(_channel.requestPrivilegedPermission(provider));
  }

  Future<bool> requestShizukuPermission() => _thenRefresh(_channel.requestShizukuPermission());

  Future<PhoneResult> adbStartPairing() => _thenRefresh(_channel.adbStartPairing());

  Future<PhoneResult> adbPair(String code, {int? port}) => _thenRefresh(_channel.adbPair(code, port: port));

  Future<PhoneResult> adbConnect({int? port}) => _thenRefresh(_channel.adbConnect(port: port));

  /// Changes one app's rule; false when the native side refused (nothing changes).
  Future<bool> setRule(String package, AppRule rule) async {
    final current = state.policies;
    if (current == null) return false;
    final next = current.withRule(package, rule);
    final ok = await _channel.setPolicies(next);
    if (!ok || !ref.mounted) return false;
    state = state.copyWith(
      policies: _acceptedPolicyWrite(next),
      retained: state.retained.contains(package) ? state.retained : [...state.retained, package],
    );
    return true;
  }

  /// 每次都询问 / 本任务免确认 / 始终免确认; false when the native side refused
  /// (nothing changes). Written, it also settles a first-run choice still
  /// pending: the latest word wins.
  Future<bool> setConfirmMode(PhoneConfirmMode mode) async {
    final current = state.policies;
    if (current == null) return false;
    final next = current.withConfirmMode(mode);
    final ok = await _channel.setPolicies(next);
    if (!ok || !ref.mounted) return false;
    state = state.copyWith(policies: _acceptedPolicyWrite(next));
    await _clearPendingConfirmMode();
    return true;
  }

  // The old setter writes these three fields. Preserve newer read-only settings,
  // unlocks and OCR state rather than restoring the pre-await object.
  PhonePolicies? _acceptedPolicyWrite(PhonePolicies written) {
    final current = state.policies;
    if (current == null) return null;
    return PhonePolicies(
      denyPackages: written.denyPackages,
      confirmEveryWritePackages: written.confirmEveryWritePackages,
      confirmMode: written.confirmMode,
      labels: current.labels,
      defaultDeny: current.defaultDeny,
      defaultConfirm: current.defaultConfirm,
      unlocks: current.unlocks,
      localOcrEnabled: current.localOcrEnabled,
    );
  }

  Future<bool> setLocalOcrEnabled(bool enabled) async {
    if (state.policies?.localOcrEnabled == null) return false;
    final ok = await _channel.setLocalOcrEnabled(enabled);
    if (!ref.mounted) return ok;
    final readback = await _channel.getPolicies();
    if (!ref.mounted) return ok;
    final current = state.policies;
    if (current != null) {
      state = state.copyWith(policies: current.withLocalOcrEnabled(readback?.localOcrEnabled));
    }
    return ok;
  }

  /// First run's 关键步骤先问我 / 全程自动, through the same `setPolicies` as
  /// 设置 › 手机操控 › 确认方式. Kept (`phone_confirm_pending`) until written:
  /// with the native side not answering yet, or a write that failed, it goes
  /// in on the next read of the policies (the summary's 继续, the app's next
  /// start, opening 设置 › 手机操控). True once written.
  Future<bool> chooseConfirmMode(PhoneConfirmMode mode) async {
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setString(confirmPendingKey, mode.name);
    } catch (_) {
      // No preferences backend: this one attempt is all there is.
      return _applyPending(mode);
    }
    // One in flight read the choice before this one: let it land, then write this.
    await _applying;
    return applyPendingConfirmMode();
  }

  /// Writes a pending first-run choice; true when none is left. One attempt
  /// at a time.
  Future<bool> applyPendingConfirmMode() => _applying ??= () async {
    try {
      final mode = await _pendingConfirmMode();
      return mode == null || await _applyPending(mode);
    } finally {
      _applying = null;
    }
  }();

  Future<bool> _applyPending(PhoneConfirmMode mode) async {
    if (state.policies == null) {
      await refreshStatus();
      if (!ref.mounted || !state.status.ready) return false;
      final policies = await _channel.getPolicies();
      if (!ref.mounted || policies == null) return false;
      state = state.copyWith(policies: policies);
    }
    return setConfirmMode(mode);
  }

  Future<PhoneConfirmMode?> _pendingConfirmMode() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      return PhoneConfirmMode.values.asNameMap()[prefs.getString(confirmPendingKey)];
    } catch (_) {
      return null;
    }
  }

  Future<void> _clearPendingConfirmMode() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      if (prefs.containsKey(confirmPendingKey)) await prefs.remove(confirmPendingKey);
    } catch (_) {}
  }

  /// 解除限制: lifts or restores one hard rule. Lifting is refused while any
  /// task may act (read afresh; native refuses it too): the agent driving the
  /// phone must not widen its own reach. False when refused or the native side
  /// said no (nothing changes).
  Future<bool> setUnlock(PhoneUnlock unlock, bool on) async {
    if (state.policies == null) return false;
    if (on) {
      await refreshStatus();
      if (!ref.mounted || state.status.anyTaskBusy) return false;
    }
    final ok = await _channel.setUnlock(unlock, on);
    if (!ok || !ref.mounted) return false;
    final current = state.policies;
    if (current != null) state = state.copyWith(policies: current.withUnlock(unlock, on));
    return true;
  }

  Future<T> _thenRefresh<T>(Future<T> call) async {
    final result = await call;
    if (ref.mounted) unawaited(refreshStatus());
    return result;
  }

  Future<bool> setSkillSavePrompt(bool enabled) => _thenRefresh(_channel.setSkillSavePrompt(enabled));
}

final phoneProvider = NotifierProvider<PhoneController, PhoneState>(PhoneController.new);

/// Why phone control can't work right now; null when nothing stands in the way.
enum PhoneBlocker {
  /// The native module didn't answer: 未就绪.
  notReady,

  /// The local engine runs (or will start) the rescue profile, which loads no plugins.
  safeMode,

  /// The chat is on a remote host; phone tools exist only in the local engine.
  remoteHost,
}

final phoneBlockerProvider = Provider<PhoneBlocker?>((ref) {
  final phone = ref.watch(phoneProvider.select((s) => (s.loaded, s.status.ready)));
  if (phone.$1 && !phone.$2) return PhoneBlocker.notReady;
  if (ref.watch(localRuntimeProvider.select((s) => s.status.inSafeMode))) {
    return PhoneBlocker.safeMode;
  }
  if (!ref.watch(activeHostProvider).isLocal) return PhoneBlocker.remoteHost;
  return null;
});

/// A first-run 确认方式 still pending ([PhoneController.chooseConfirmMode]) is
/// written once the home shell is up; the policies' next read tries again.
final phoneConfirmPendingProvider = Provider<void>((ref) {
  unawaited(() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      if (prefs.getString(PhoneController.confirmPendingKey) == null) return;
    } catch (_) {
      return;
    }
    if (!ref.mounted) return;
    await ref.read(phoneProvider.notifier).applyPendingConfirmMode();
  }());
});

/// The phone control's stop is native and session-scoped. The original
/// cancel-all watcher is intentionally gone: this app runs several local
/// conversations (the Orb, the app), and a phone stop in one must not cancel
/// another's running turn. A stopped task refuses its own tools; the agent
/// reads that result and concludes, and the next send resumes the task.
final phoneStopWatcherProvider = Provider<void>((ref) {});
