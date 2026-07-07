package net.jan.moddirector.standalone;

import net.jan.moddirector.core.ModDirector;

import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

public class ModDirectorStandalone {
    public static void main(String[] args) throws Exception {
        if(args.length >= 1 && "--validate".equals(args[0])) {
            if(args.length < 2) {
                System.err.println("usage: --validate <config-dir>");
                System.exit(2);
                return;
            }
            System.exit(ModDirectorValidator.run(Paths.get(args[1])));
            return;
        }

        ModDirectorStandalonePlatform platform = new ModDirectorStandalonePlatform();
        ModDirector director = ModDirector.bootstrap(platform);

        if(!director.activate(Long.MAX_VALUE, TimeUnit.DAYS)) {
            director.errorExit();
        }

        System.out.println("============================================================");
        System.out.println("Installed mods summary:");
        System.out.println("============================================================");
        director.getInstalledMods().forEach((mod) -> {
            System.out.println(mod.getFile() + (mod.shouldInject() ? " has been injected" : " has not been injected"));
            mod.getOptions().forEach((key, value) -> System.out.println("- " + key + ": " + value));
        });
        System.out.println("============================================================");
    }
}
