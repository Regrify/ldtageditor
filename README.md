# Lego Dimensions Tag Editor v3

## Planned features:
- get information from the nfc tag like current character or vehicle in the memory
- from that display the internal id for development
- being able to add new characters
- support for unreleased characters
- categories for selections


forked from https://github.com/omer-yalcin/ldtageditor which was forked from https://github.com/naleo/ldtageditor-v2
# ldtageditor-v2
Lego Dimensions Tag Editor - Updated

This is an update version of the Lego Dimensions Tag Editor.

There was an issue where if an android phone does not provide the MifareUltralight NFC api, the app simply does not work.  I replaced all API calls to this with calls to the NfcA API, which all phones with NFC capability on android are required to support.  This fixes the issues I had with the app.

Added Android 16 support
Added images to selected tags
Added borders to the images to reflect the pack colors
