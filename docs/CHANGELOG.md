# CompixelUI 0.1.10-alpha.6

- New: `ScreenTransition` animates a screen's content in when the screen opens and out when it closes, using Compose's enter and exit transitions, which can differ. Closing gives the player control back at once while the exit plays above the game. See [Screen transitions](https://github.com/Frostbite-time/CompixelUI/blob/main/docs/en/transitions.md).
- Changed: a container screen that a recipe viewer or another screen covers while its menu stays open keeps its Compose session. When it shows again, its content is as it was, including `remember` state, scroll positions and typed text, and its entrance doesn't play again.
- Fixed: `menuClosed()` now runs when the menu closes while another screen covers the container screen.
- Fixed: an enter animation that starts with a screen's content, such as `AnimatedVisibility` with a `MutableTransitionState`, now plays from the first frame instead of jumping to its end.
