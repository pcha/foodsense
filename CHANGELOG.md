## [0.9.1](https://github.com/pcha/foodsense/compare/v0.9.0...v0.9.1) (2026-10-13)



# [0.9.0](https://github.com/pcha/foodsense/compare/v0.8.0...v0.9.0) (2026-10-12)


### Bug Fixes

* **ci:** run available unit tests in release ([4230890](https://github.com/pcha/foodsense/commit/4230890d132aaa8f6eed25e140d13953634d1fbc))
* harden scanner error handling ([d242e83](https://github.com/pcha/foodsense/commit/d242e835199376986591407aa4186997fc30c2c4))
* normalize centiliters to milliliters ([e4c75ef](https://github.com/pcha/foodsense/commit/e4c75efc9c7fe0df6aaebedbc3f7d30b08a68849))
* **sync:** merge same-named products instead of dropping one ([63141fd](https://github.com/pcha/foodsense/commit/63141fd67fe8af893e89f502be804722030155a7))
* validate release google services package ([077f1bd](https://github.com/pcha/foodsense/commit/077f1bd3795400475af4bbaeb3536df9c8f7d61d))


### Features

* **android:** add separate development launcher icon ([0b49547](https://github.com/pcha/foodsense/commit/0b4954742e959bbb90cdeec8b5d2a0ab13075e73))
* **auth:** expose a domain User and move onboarding to DataStore ([f7110f9](https://github.com/pcha/foodsense/commit/f7110f9d6178386d87a704ca888d235a8d9d1409))
* **i18n:** standardise on an English default with a Spanish translation ([513c52d](https://github.com/pcha/foodsense/commit/513c52ddadcacc371db21fef7673efd3d8bc98dc))
* rename package to dev.pcha, add Firebase auth and Firestore sync ([260ff1e](https://github.com/pcha/foodsense/commit/260ff1e231745e89cf2f2fc8ab6a06fc2b86e774))
* **sync:** reconcile periodically with a sync worker ([4b4901d](https://github.com/pcha/foodsense/commit/4b4901da631068697ed56021b673b2c31f2a2ee5))



# [0.8.0](https://github.com/pcha/foodsense/compare/v0.7.1...v0.8.0) (2026-05-17)


### Features

* **scan:** add barcode scanner, OCR date scanner, and barcode registry ([00b7d11](https://github.com/pcha/foodsense/commit/00b7d119eb610f78b2cfae8aa4279e57d895ba01))



## [0.7.1](https://github.com/pcha/foodsense/compare/v0.7.0...v0.7.1) (2026-05-04)


### Bug Fixes

* resolve compiler errors and IDE warnings in ProductScreen ([ce77148](https://github.com/pcha/foodsense/commit/ce771480115729028c8efc90f800f75ce514f400))



# [0.7.0](https://github.com/pcha/foodsense/compare/v0.6.0...v0.7.0) (2026-05-02)


### Bug Fixes

* **ui:** adjust color thresholds to use inclusive ranges ([7a61db8](https://github.com/pcha/foodsense/commit/7a61db8745e2b5840ccead7a15a741e2e9ff9a5d))


### Features

* **ui:** add collapsable sections, expiry colors, and smart date labels ([f202783](https://github.com/pcha/foodsense/commit/f202783aeb1152437983770daa5705d32169b14d))



# [0.6.0](https://github.com/pcha/foodsense/compare/v0.5.0...v0.6.0) (2026-05-02)


### Features

* **ui:** show relative added date when no expiration date is set ([f04a710](https://github.com/pcha/foodsense/commit/f04a710e0205abf09254a83c4ca92d062b5b088f))



# [0.5.0](https://github.com/pcha/foodsense/compare/v0.4.1...v0.5.0) (2026-05-02)


### Features

* **expiry:** add intelligent labels and color-coded warning system ([46fc498](https://github.com/pcha/foodsense/commit/46fc498ecbc29273e31c5f867a82bc00af796c5b)), closes [#FF9800](https://github.com/pcha/foodsense/issues/FF9800) [#4CAF50](https://github.com/pcha/foodsense/issues/4CAF50)



## [0.4.1](https://github.com/pcha/foodsense/compare/v0.4.0...v0.4.1) (2026-05-02)


### Bug Fixes

* suppress MissingPermission lint in notification worker ([0687e07](https://github.com/pcha/foodsense/commit/0687e0722b9f857fe2f1101ea1ba798f810ae657))



# [0.4.0](https://github.com/pcha/foodsense/compare/v0.3.0...v0.4.0) (2026-05-02)


### Features

* autocomplete product name from history ([e3433cb](https://github.com/pcha/foodsense/commit/e3433cb28443cfb76bad4c808533643d75a77d93))



# [0.3.0](https://github.com/pcha/foodsense/compare/v0.2.0...v0.3.0) (2026-05-01)


### Features

* add item editing, quick-add, and batch actions to product card ([12d3c7e](https://github.com/pcha/foodsense/commit/12d3c7eba8d3c96ed9d54c36e7e25f952111579f))
* split product into product+item two-table model ([e2b5226](https://github.com/pcha/foodsense/commit/e2b52267e07870bc7fbbce7eea9df6cdfc6a891e))



# [0.2.0](https://github.com/pcha/foodsense/compare/v0.1.0...v0.2.0) (2026-04-28)


### Features

* **notifications:** add daily expiration alert for products expiring tomorrow ([0413d1c](https://github.com/pcha/foodsense/commit/0413d1c99b061c036f46e0ed522ba8596f1342f8))



# [0.1.0](https://github.com/pcha/foodsense/compare/887670a3ced016d318b25dc67425946c7210fb85...v0.1.0) (2026-04-13)


### Features

* add full product CRUD with quantity, expiration date, and form UI ([4fa9e40](https://github.com/pcha/foodsense/commit/4fa9e4065c910a29fcfafaaeb049aa9a9d4e2976))
* initial commit ([887670a](https://github.com/pcha/foodsense/commit/887670a3ced016d318b25dc67425946c7210fb85))



