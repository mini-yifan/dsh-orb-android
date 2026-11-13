// The local Orb conversation the floating strip and its bookmarks follow.
// Kept apart from the action handler so the bookmark controller can read it
// without importing the whole overlay action surface.

import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'pet_state.dart';

class OrbSessionController extends Notifier<PetTarget?> {
  @override
  PetTarget? build() => null;

  void remember(PetTarget target) => state = target;
  void forget() => state = null;
}

final orbSessionProvider = NotifierProvider<OrbSessionController, PetTarget?>(OrbSessionController.new);