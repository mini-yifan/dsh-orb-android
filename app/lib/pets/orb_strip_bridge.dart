// Feeds the floating strip's live status (`pushOrb`) from the local Orb
// conversation. Kept alive by [petOverlayBridgeProvider].

import 'dart:async';

import 'package:dsh_client/dsh_client.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../state/chat_state.dart';
import '../state/hosts_state.dart';
import 'overlay_channel.dart';
import 'pet_overlay_actions.dart';

/// What the strip needs, reduced so assistant deltas do not push every frame:
/// running/waiting, the current tool, and whether stop can act.
class OrbStripStatus {
  const OrbStripStatus({
    required this.state,
    required this.running,
    required this.canStop,
    this.tool,
    this.text,
  });

  /// running | waiting-approval | waiting-question | done | error
  final String state;
  final bool running;
  final bool canStop;
  final String? tool;
  final String? text;

  static OrbStripStatus of(ChatState s) {
    final tool = s.running
        ? s.items
            .reversed
            .whereType<ToolChatItem>()
            .where((t) =>
                t.status == ToolStatus.running ||
                t.status == ToolStatus.streaming ||
                t.status == ToolStatus.awaitingApproval)
            .firstOrNull
            ?.name
        : null;
    final canStop = s.running && !s.stopping;
    if (s.openApproval != null) {
      return OrbStripStatus(state: 'waiting-approval', running: s.running, canStop: canStop, tool: tool);
    }
    if (s.questions.isNotEmpty) {
      return OrbStripStatus(state: 'waiting-question', running: s.running, canStop: canStop, tool: tool);
    }
    if (s.running) return OrbStripStatus(state: 'running', running: true, canStop: canStop, tool: tool);
    if (s.error != null) return OrbStripStatus(state: 'error', running: false, canStop: false, text: s.error);
    return const OrbStripStatus(state: 'done', running: false, canStop: false);
  }

  @override
  bool operator ==(Object other) =>
      other is OrbStripStatus &&
      other.state == state &&
      other.running == running &&
      other.canStop == canStop &&
      other.tool == tool &&
      other.text == text;

  @override
  int get hashCode => Object.hash(state, running, canStop, tool, text);
}

/// Pushes `pushOrb` when the Orb session's status changes. The provider is
/// watched by the pet overlay bridge (the home shell), so it lives while the
/// overlay is fed.
final orbStripBridgeProvider = Provider<void>((ref) {
  final channel = ref.watch(petOverlayChannelProvider);
  final target = ref.watch(orbSessionProvider);
  // The strip follows the phone's own engine only; a remote active host hides it.
  if (target == null || ref.watch(activeHostProvider) != HostRef.local) {
    unawaited(channel.pushOrb(const {'visible': false}));
    return;
  }
  final status = ref.watch(chatProvider(target.sessionId).select(OrbStripStatus.of));
  unawaited(
    channel.pushOrb({
      'visible': true,
      'sessionId': target.sessionId,
      'host': target.host.encode(),
      'state': status.state,
      'running': status.running,
      'canStop': status.canStop,
      if (status.tool != null) 'tool': status.tool,
      if (status.text != null) 'text': status.text,
    }),
  );
});