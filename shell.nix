{ pkgs ? import <nixpkgs> {} }:

pkgs.mkShell {
  packages = [
    pkgs.jdk11
  ];

  shellHook = ''
    export JAVA_HOME="${pkgs.jdk11}"
    echo "Bank Tags Extended development shell"
    java -version
  '';
}
