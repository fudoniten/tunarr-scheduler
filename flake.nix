{
  description = ''
    Tunarr Scheduler -- Schedule Tunarr channels with LLM
      assistance.'';

  inputs = {
    nixpkgs.url = "nixpkgs/nixos-25.05";
    utils.url = "github:numtide/flake-utils";
    nix-helpers = {
      url = "github:fudoniten/fudo-nix-helpers";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs = { self, nixpkgs, utils, nix-helpers, ... }:
    utils.lib.eachDefaultSystem (system:
      let
        pkgs = nixpkgs.legacyPackages."${system}";
        helpers = nix-helpers.legacyPackages."${system}";
        cljLibs = { };
      in {
        packages = rec {
          default = tunarrScheduler;

          tunarrScheduler = helpers.mkClojureBin {
            name = "org.fudo/tunarr-scheduler";
            primaryNamespace = "tunarr.scheduler.main";
            src = ./.;
          };

          migratusRunner = helpers.mkClojureBin {
            name = "org.fudo/tunarr-scheduler-migrate";
            primaryNamespace = "app.migrate";
            src = ./.;
          };

          deployContainer = helpers.deployContainers {
            name = "tunarr-scheduler";
            repo = "registry.kube.sea.fudo.link";
            tags = [ "latest" ];
            entrypoint =
              let tunarrScheduler = self.packages."${system}".tunarrScheduler;
              in [ "${tunarrScheduler}/bin/tunarr-scheduler" ];
            verbose = true;
          };

          migrationContainer = helpers.deployContainers {
            name = "tunarr-scheduler-migratus";
            repo = "registry.kube.sea.fudo.link";
            tags = [ "latest" ];
            entrypoint =
              let migratus = self.packages."${system}".migratusRunner;
              in [ "${migratus}/bin/tunarr-scheduler-migrate" ];
            verbose = true;
          };
        };

        checks = {
          clojureTests = pkgs.runCommand "clojure-tests" { } ''
            mkdir -p $TMPDIR
            cd $TMPDIR
            ${pkgs.clojure}/bin/clojure -M:test
          '';
        };

        devShells = rec {
          default = updateDeps;
          updateDeps = pkgs.mkShell {
            buildInputs = [ (helpers.updateClojureDeps cljLibs) ];
          };
          tunarrSchedulerServer = pkgs.mkShell {
            buildInputs = with self.packages."${system}"; [ tunarrScheduler ];
          };
        };

        apps = rec {
          default = deployContainer;
          deployContainer = {
            type = "app";
            program =
              let deployContainer = self.packages."${system}".deployContainer;
              in "${deployContainer}/bin/deployContainers";
          };
          migrationContainer = {
            type = "app";
            program = let
              migrationContainer = self.packages."${system}".migrationContainer;
            in "${migrationContainer}/bin/deployContainers";
          };
        };
      });
}
