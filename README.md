
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
reports including a heuristic explanation of likely causes. The analyzer is
structured so that future versions can hook into an AI service for deeper log
diagnostics. By default the helper uses a simple heuristic implementation, but
if the config value `logging.aiServiceApiKey` or the environment variable
`DEBUG_GUARDIAN_AI_KEY` is defined it will attempt to contact an external
service using that key via `AiLogAnalyzer`.

The included `AiLogAnalyzer` class demonstrates how an AI service could be
invoked. It first reads the API key from the `logging.aiServiceApiKey` config
entry, then falls back to `DEBUG_GUARDIAN_AI_KEY`, and finally uses a
placeholder that must be replaced with a real key before any external requests
are made.

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
