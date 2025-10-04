{
  description = "Tunarr Scheduler service";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-23.11";

  outputs = { self, nixpkgs }:
    let
      systems = [ "x86_64-linux" "aarch64-linux" ];
      forAllSystems = nixpkgs.lib.genAttrs systems;
    in {
      devShells = forAllSystems (system:
        let
          pkgs = import nixpkgs { inherit system; };
        in pkgs.mkShell {
          buildInputs = [
            pkgs.clojure
            pkgs.openjdk21
            pkgs.clj-kondo
            pkgs.babashka
            pkgs.git
          ];
        });
    };
}
