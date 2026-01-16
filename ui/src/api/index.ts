import { axiosInstance } from "@halo-dev/api-client";
import {
  // Extension CRUD APIs
  LotteryActivityV1alpha1Api,
  LotteryParticipantV1alpha1Api,
  // Custom Endpoint APIs
  LotteryV1alpha1PublicApi,
  LotteryV1alpha1ConsoleApi,
  LotteryV1alpha1UcApi,
} from "./generated";

// Extension CRUD API clients
const lotteryActivityApi = new LotteryActivityV1alpha1Api(undefined, "", axiosInstance);
const lotteryParticipantApi = new LotteryParticipantV1alpha1Api(undefined, "", axiosInstance);

// Custom Endpoint API clients
const lotteryPublicApi = new LotteryV1alpha1PublicApi(undefined, "", axiosInstance);
const lotteryConsoleApi = new LotteryV1alpha1ConsoleApi(undefined, "", axiosInstance);
const lotteryUcApi = new LotteryV1alpha1UcApi(undefined, "", axiosInstance);

export {
  // Extension CRUD
  lotteryActivityApi,
  lotteryParticipantApi,
  // Custom Endpoints
  lotteryPublicApi,
  lotteryConsoleApi,
  lotteryUcApi,
};

// Re-export types
export * from "./generated";
