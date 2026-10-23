# CompixelUI 0.1.15-alpha.1

- Changed: the first CompixelUI screen of a game session stalls the game much less when it opens. CompixelUI now prepares Compose in the background while the game loads; the first opening of a container screen went from about 0.7 to about 0.25 seconds.
- Fixed: animations that start when a screen opens, such as its entrance, play from the frame in which the screen first shows. The time a screen spent composing and drawing its first frame used to count toward them, so a slow first opening could skip an entrance entirely.
