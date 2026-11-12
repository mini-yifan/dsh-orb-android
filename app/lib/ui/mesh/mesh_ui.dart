import 'package:flutter/material.dart';

import '../../l10n/l10n.dart';

String meshStatusLabel(L10n l10n, String status) => switch (status) {
  'running' => l10n.meshRunning,
  'waiting-approval' => l10n.meshWaitingApproval,
  'waiting-question' => l10n.meshWaitingQuestion,
  'failed' => l10n.meshFailed,
  'idle' => l10n.meshIdle,
  _ => l10n.meshUnknown,
};

/// `15:30` for today's sessions, the short date otherwise. Kept local to the
/// Mesh pages so the session lists do not pull in the forum time formatter.
String meshUpdatedLabel(BuildContext context, int at) {
  if (at <= 0) return '';
  final date = DateTime.fromMillisecondsSinceEpoch(at);
  final now = DateTime.now();
  if (date.year == now.year && date.month == now.month && date.day == now.day) {
    return MaterialLocalizations.of(context).formatTimeOfDay(
      TimeOfDay.fromDateTime(date),
      alwaysUse24HourFormat: true,
    );
  }
  return MaterialLocalizations.of(context).formatShortDate(date);
}