
Installation information
=======

This template repository can be directly cloned to get you started with a new
mod. Simply create a new repository cloned from this one, by following the
instructions provided by [GitHub](https://docs.github.com/en/repositories/creating-and-managing-repositories/creating-a-repository-from-a-template).

Once you have your clone, simply open the repository in the IDE of your choice. The usual recommendation for an IDE is either IntelliJ IDEA or Eclipse.

If at any point you are missing libraries in your IDE, or you've run into problems you can
run `gradlew --refresh-dependencies` to refresh the local cache. `gradlew clean` to reset everything
{this does not affect your code} and then start the process again.

Debug Helper
------------
When debug mode is enabled the project launches a small helper application that
monitors crash dumps. It now parses force-close logs and generates multiple
reports including a heuristic explanation of likely causes. Force-close dumps
include each thread's state, and the analyzer flags when no mod-specific stack
frames are found, aiding cases where the process was terminated without a clear
trace. The analyzer is structured so that future versions can hook into an AI
service for deeper log diagnostics. If the config value `logging.aiServiceApiKey`
or the environment variable `DEBUG_GUARDIAN_AI_KEY` is defined the helper
contacts OpenAI using `AiLogAnalyzer` to generate an explanation. When no key is
provided the analyzer automatically falls back to the built-in heuristic
`BasicLogAnalyzer`.

The included `AiLogAnalyzer` class sends thread reports to the OpenAI Chat
Completions API. It first reads the API key from the `logging.aiServiceApiKey`
config entry, then falls back to `DEBUG_GUARDIAN_AI_KEY`. If neither is
supplied it invokes the heuristic analyzer instead.

Runtime monitors like `GcPauseMonitor`, `WorldHangDetector`, and the new
`MemoryLeakMonitor` provide proactive warnings about server health, catching
issues such as long GC pauses, hung ticks, or sustained high heap usage.

Mapping Names:
============
By default, the MDK is configured to use the official mapping names from Mojang for methods and fields 
in the Minecraft codebase. These names are covered by a specific license. All modders should be aware of this
license. For the latest license text, refer to the mapping file itself, or the reference copy here:
https://github.com/NeoForged/NeoForm/blob/main/Mojang.md

Additional Resources: 
==========
Community Documentation: https://docs.neoforged.net/  
NeoForged Discord: https://discord.neoforged.net/
