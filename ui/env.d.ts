/// <reference types="@rsbuild/core/types" />
/// <reference types="unplugin-icons/types/vue" />

export {};

declare module "axios" {
  export interface AxiosRequestConfig {
    mute?: boolean;
  }
}

declare module "*.vue" {
  import type { ComponentOptions } from "vue";
  const Component: ComponentOptions;
  export default Component;
}

declare module "vue" {
  interface ComponentCustomProperties {
    $formkit: any;
  }
}

// Halo console global components
declare module "@vue/runtime-core" {
  export interface GlobalComponents {
    SearchInput: typeof import("@halo-dev/components")["SearchInput"];
    FilterCleanButton: typeof import("@halo-dev/components")["FilterCleanButton"];
    FilterDropdown: typeof import("@halo-dev/components")["FilterDropdown"];
    HasPermission: typeof import("@halo-dev/components")["HasPermission"];
  }
}