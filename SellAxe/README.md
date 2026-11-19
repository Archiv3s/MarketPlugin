# SellAxe

A Paper plugin for this server. `/sellaxe` gives a Sell Axe. Breaking a chest, barrel or shulker box with it sells the items inside at the EconomyShopGUI sell price and pays the money through Vault. Then the container breaks normally.

## Requirements
- Paper 26.3 (the build in use prints `26.3-245-main`)
- Vault
- EconomyShopGUI (its shop YAML files are read at startup)

## Build
```
cd SellAxe
mvn package
```
The jar is `SellAxe/target/SellAxe-1.0.0.jar`. Copy it to the server's `plugins/` folder and restart.

## Notes
- Prices come from every YAML file under `plugins/EconomyShopGUI` that has a `pages:` section. The first sell price found for each material is used.
- Items that are enchanted or renamed are not sold, so they stay in the container.
- Prices load at startup. Restart the server after editing shop files.
- Not compiled or run yet. The Paper API and Vault artifact versions are unverified until the first `mvn package`.
