# CompixelUI 0.1.12

- Fixed: holding a key such as Shift, Ctrl or Space in a CompixelUI screen no longer makes the game stutter. Every key press, including the repeats of a held key, used to read the system clipboard, which can take tens of milliseconds depending on what was copied. Now only the paste shortcuts read it, once per press: Ctrl+V, Command+V on macOS, and Shift+Insert.
