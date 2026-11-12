// User actions from the floating pet. Offers pin the host, connection and
// generation; sending still goes through ChatController, including sync,
// history, prompt receipts and retries. No background host is selected here.
import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:dsh_client/dsh_client.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../l10n/l10n.dart';
import '../state/attachments.dart';
import '../state/chat_state.dart';
import '../state/compose_inbox.dart';
import '../state/connection_state.dart';
import '../state/hosts_state.dart';
import '../state/notify_state.dart';
import '../state/sessions_state.dart';
import '../voice/voice_model_assets.dart';
import 'overlay_channel.dart';
import 'pet_signals.dart';
import 'pet_state.dart';

/// Unlike persisted host preferences, an action must never decode malformed
/// input as permission to act on the local engine.
HostRef? petActionHost(Object? raw) {
  if (raw is! String) return null;
  try {
    final m = jsonDecode(raw);
    if (m is! Map || m['id'] is! String || (m['id'] as String).isEmpty) return null;
    return switch (m['kind']) {
      'local' when m['id'] == 'local' => HostRef.local,
      'ssh' => HostRef.ssh(m['id'] as String),
      'relay' => HostRef.relay(m['id'] as String),
      _ => null,
    };
  } catch (_) {
    return null;
  }
}

final petHostChoicesProvider = Provider<List<PetHostChoice>>((ref) {
  final current = ref.watch(activeHostProvider);
  final hosts = <(HostRef, String)>[
    (HostRef.local, appL10n.commonThisPhone),
    for (final h in ref.watch(hostsProvider).ssh)
      if (h.hostKeyFingerprint != null) (HostRef.ssh(h.id), hostLabel(HostKind.ssh, h.name)),
    for (final h in ref.watch(relayAccountProvider).value?.hosts ?? <RelayHost>[])
      if (h.trust == RelayTrust.pinned) (HostRef.relay(h.id, name: h.name), hostLabel(HostKind.relay, h.name)),
  ];
  return [for (final (host, label) in hosts) PetHostChoice(host, snippet(label, max: 40)!, current: host == current)];
});

/// The floating ball's own preset: Computer Use on this phone
/// (runtime/bridge/dsh-go-bridge/cordis.patch.yml). A runtime without it falls
/// back to the host default instead of failing the send.
const orbPhonePreset = 'orb-phone';

class _AskOffer {
  _AskOffer(this.id, this.host, this.connection, this.generation, this.sessionId, this.local);
  final int id;
  final HostRef host;
  final HostConnection? connection;
  final MuxGeneration? generation;
  final String? sessionId;

  /// This phone's own engine: the only host that has [orbPhonePreset] and the
  /// only one whose sends are paired with a screenshot of this phone's screen.
  final bool local;
  String? created;
}

class PetOverlayActions {
  PetOverlayActions(this.ref);
  final Ref ref;
  _AskOffer? _offer;
  int _next = 0;
  bool _sending = false;

  bool _current(_AskOffer offer) =>
      ref.mounted &&
      ref.read(activeHostProvider) == offer.host &&
      identical(ref.read(activeConnectionProvider).value, offer.connection) &&
      identical(offer.connection?.client.mux.generation, offer.generation);

  Future<Object?> handle(MethodCall call) async {
    final args = call.arguments is Map ? call.arguments as Map : const {};
    switch (call.method) {
      case 'askTarget':
        return _target(args['fresh'] == true);
      case 'ask':
        return _send(args);
      case 'answer':
        final host = petActionHost(args['host']);
        final event = args['eventId'];
        final session = args['sessionId'];
        final token = args['token'];
        final decision = switch (args['decision']) {
          'allow' => ApprovalDecision.allowOnce,
          'reject' => ApprovalDecision.reject,
          _ => null,
        };
        final kind = args['kind'];
        if (host == null ||
            event is! String ||
            session is! String ||
            token is! int ||
            (kind != 'approval' && kind != 'question') ||
            (kind == 'approval' && decision == null) ||
            (kind == 'question' && args['choice'] is! String)) {
          return {'result': 'failed'};
        }
        final result = await ref
            .read(chatAlertsProvider.notifier)
            .answerFromPet(
              host,
              event,
              sessionId: session,
              token: token,
              decision: kind == 'approval' ? decision : null,
              choice: kind == 'question' ? args['choice'] as String : null,
            );
        return {'result': result.name};
      case 'transcribe':
        return _transcribe(args['wav']);
      case 'switchHost':
        final host = petActionHost(args['host']);
        if (host == null || !ref.read(petHostChoicesProvider).any((h) => h.host == host)) return false;
        ref.read(activeHostProvider.notifier).select(host);
        _offer = null;
        return true;
      case 'stop':
        final host = petActionHost(args['host']);
        final session = args['sessionId'];
        if (host != ref.read(activeHostProvider) || session is! String) return false;
        final target = ref.read(petStateProvider).target;
        if (target != PetTarget(host!, session)) return false;
        final chat = ref.read(chatProvider(session).notifier);
        if (!await chat.waitForSync() || !ref.mounted || ref.read(activeHostProvider) != host) return false;
        final returned = await chat.cancel();
        if (ref.mounted) {
          for (final prompt in returned) {
            ref
                .read(composeInboxProvider.notifier)
                .push(ComposeRequest(text: prompt.text, attachments: prompt.attachments));
          }
        }
        return true;
      default:
        return null;
    }
  }

  Future<Map<String, Object?>> _target(bool fresh) async {
    final host = ref.read(activeHostProvider);
    final connection = ref.read(activeConnectionProvider).value;
    final generation = connection?.client.mux.generation;
    final pet = ref.read(petStateProvider).target;
    final sessions = ref.read(sessionsProvider).value ?? const <SessionSummary>[];
    final session = pet?.host == host
        ? sessions.where((s) => s.sessionId == pet!.sessionId && !s.running && s.agentAvailable).firstOrNull
        : null;
    // The Computer Use preset only exists in this phone's engine. On a remote
    // host the ball keeps its old behavior: the host's default preset, no
    // screenshot of this phone's screen.
    final local = host == HostRef.local;
    // No current conversation means the bar is on 「新对话」, which is the Orb.
    final wantFresh = fresh || session == null;
    final shot = local && (wantFresh || session.projections.agentPreset == orbPhonePreset);
    final offer = _AskOffer(++_next, host, connection, generation, session?.sessionId, local);
    _offer = offer;
    final voice = await _voice();
    final label = snippet(connection?.label ?? hostLabel(host.kind, host.name), max: 40)!;
    return {
      'offer': offer.id,
      'fresh': {'host': host.encode(), 'line': '${appL10n.commonNewChat} · $label'},
      if (session != null)
        'current': {
          'host': host.encode(),
          'sessionId': session.sessionId,
          'line': '${snippet(session.title, max: 20) ?? appL10n.commonUntitledChat} · $label',
        },
      // The native bar hides itself and attaches a capture of what is behind it.
      'shot': shot,
      if (connection == null || generation == null) 'problem': appL10n.commonEngineUnreachable,
      'voice': ref.read(localConnectionProvider).value?.client.mux.generation == null
          ? 'off'
          : voice == null
          ? 'missing'
          : 'ready',
    };
  }

  Future<Map<String, Object?>> _send(Map args) async {
    Map<String, Object?> problem(String text) => {'problem': text};
    final offer = _offer;
    final text = args['text'];
    final fresh = args['fresh'] == true;
    // The native bar captures before it knows whether this send will be accepted;
    // read and delete the cache slot on every path, then keep the image only for
    // the Orb.
    final captured = await _screenshotAttachments(args['screenshot']);
    if (_sending) return problem(appL10n.petActionSending);
    if (offer == null ||
        args['offer'] != offer.id ||
        !_current(offer) ||
        petActionHost(args['host']) != offer.host ||
        (!fresh && args['sessionId'] != offer.sessionId) ||
        (!fresh && offer.sessionId == null)) {
      return problem(appL10n.petActionTargetChanged);
    }
    if (offer.connection == null || offer.generation == null) return problem(appL10n.commonEngineUnreachable);
    if (text is! String || text.trim().isEmpty || text.length > 4000) return problem(appL10n.petActionSendFailed);
    final orb = offer.local && (fresh || _presetOf(offer.sessionId) == orbPhonePreset);
    _sending = true;
    try {
      final id = fresh ? offer.created ??= await _create(offer) : offer.sessionId!;
      if (!_current(offer)) return problem(appL10n.petActionTargetChanged);
      final chat = ref.read(chatProvider(id).notifier);
      if (!await chat.waitForSync(isCurrent: () => _current(offer)) || !_current(offer)) {
        return problem(appL10n.commonEngineUnreachable);
      }
      final state = ref.read(chatProvider(id));
      if (state.running || state.openApproval != null || state.questions.isNotEmpty) {
        return problem(appL10n.petActionBusy);
      }
      if (!_current(offer)) return problem(appL10n.petActionTargetChanged);
      final target = PetTarget(offer.host, id);
      ref.read(petAskedProvider.notifier).add(target);
      final sent = await chat.send(text, attachments: orb ? captured : const <DraftAttachment>[]);
      if (!ref.mounted) return problem(appL10n.petActionSendFailed);
      if (!sent) {
        ref.read(petAskedProvider.notifier).take(target);
        return problem(
          _current(offer)
              ? ref.read(chatProvider(id)).error ?? appL10n.petActionSendFailed
              : appL10n.petActionTargetChanged,
        );
      }
      return {'sent': true, 'sessionId': id, 'host': offer.host.encode()};
    } catch (_) {
      return problem(appL10n.petActionSendFailed);
    } finally {
      _sending = false;
    }
  }

  /// The preset a conversation currently runs, if the list has caught up.
  String? _presetOf(String? sessionId) {
    if (sessionId == null) return null;
    final sessions = ref.read(sessionsProvider).value ?? const <SessionSummary>[];
    return sessions.where((s) => s.sessionId == sessionId).firstOrNull?.projections.agentPreset;
  }

  /// A new chat for the ball: this phone's Orb preset, else the host default.
  /// A runtime that predates the preset still gets a usable chat.
  Future<String> _create(_AskOffer offer) async {
    final sessions = ref.read(sessionsProvider.notifier);
    if (offer.local) {
      try {
        return await sessions.create(agentPreset: orbPhonePreset);
      } on DshRemoteException catch (error) {
        if (!error.code.startsWith('agent-preset/')) rethrow;
        // agent-preset/not-found or /invalid: fall through to the host default.
      }
    }
    return sessions.create();
  }

  /// The native bar's capture, read and then dropped from the cache. Only an
  /// inline image DSH accepts rides along; a missing, secure or unreadable
  /// screen still sends the text.
  Future<List<DraftAttachment>> _screenshotAttachments(Object? raw) async {
    if (raw is! String || raw.isEmpty) return const [];
    final file = File(raw);
    try {
      final size = await file.length();
      if (size <= 0 || size > maxImageBytes) return const [];
      final bytes = await file.readAsBytes();
      final mediaType = inlineImageType(bytes);
      if (mediaType == null) return const [];
      final extension = switch (mediaType) {
        'image/png' => 'png',
        'image/jpeg' => 'jpg',
        'image/webp' => 'webp',
        'image/gif' => 'gif',
        _ => 'png',
      };
      final stamp = DateTime.now().millisecondsSinceEpoch;
      return [DraftAttachment(name: 'screen-$stamp.$extension', bytes: bytes, mediaType: mediaType)];
    } catch (_) {
      return const [];
    } finally {
      try {
        await file.delete();
      } catch (_) {
        // The cache slot may already be gone; the OS reclaims the rest.
      }
    }
  }

  /// A capture that arrived for a target that will not use it is read and
  /// deleted by [_screenshotAttachments]; nothing else touches the cache slot.

  /// Only the phone's installed SenseVoice model. A remote or cloud selection
  /// must never silently receive a recording from the floating microphone.
  Future<(DshClient, SpeechCatalog, MuxGeneration)?> _voice() async {
    final client = ref.read(localConnectionProvider).value?.client;
    final generation = client?.mux.generation;
    if (client == null || generation == null) return null;
    try {
      final catalog = await client.speechCatalog().timeout(const Duration(seconds: 4));
      if (!ref.mounted ||
          !identical(ref.read(localConnectionProvider).value?.client, client) ||
          !identical(generation, client.mux.generation)) {
        return null;
      }
      final model = catalog?.providers
          .where((p) => p.id == voiceModelProviderId && p.location == 'host-local' && p.usable)
          .firstOrNull;
      return model == null ? null : (client, catalog!, generation);
    } catch (_) {
      return null;
    }
  }

  Future<Map<String, Object?>> _transcribe(Object? wav) async {
    final voice = await _voice();
    if (voice == null) return {'problem': appL10n.petActionVoiceMissing};
    if (wav is! Uint8List || wav.length < 9644 || wav.length > voice.$2.maxAudioBytes) {
      return {'problem': appL10n.chatVoiceNotHeard};
    }
    try {
      final result = await voice.$1.transcribe(wav, providerId: voiceModelProviderId, language: voice.$2.language);
      if (!ref.mounted ||
          !identical(ref.read(localConnectionProvider).value?.client, voice.$1) ||
          !identical(voice.$3, voice.$1.mux.generation)) {
        return {'problem': appL10n.commonEngineUnreachable};
      }
      return {'text': result.text};
    } catch (_) {
      return {'problem': appL10n.chatVoiceNotHeard};
    }
  }
}

final petOverlayActionsProvider = Provider<PetOverlayActions>((ref) => PetOverlayActions(ref));

final petOverlayActionBridgeProvider = Provider<void>((ref) {
  final channel = ref.watch(petOverlayChannelProvider);
  final actions = ref.watch(petOverlayActionsProvider);
  channel.setActionHandler(actions.handle);
  ref.onDispose(() => channel.setActionHandler(null));
});
