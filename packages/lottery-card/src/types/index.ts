export interface Prize {
  name: string;
  description?: string;
  imageUrl?: string;
  quantity?: number;
  remaining?: number;
  probability?: number;
}

export interface Winner {
  identifier: string;
  prizeName: string;
  winTime?: string;
}

export type LotteryType = 'SCHEDULED' | 'WHEEL' | 'DRAW';
export type ParticipationType = 'NONE' | 'LOGIN' | 'COMMENT' | 'LOGIN_AND_COMMENT';
export type LotteryState = 'PENDING' | 'RUNNING' | 'ENDED' | 'DRAWN';

export interface LotteryData {
  name: string;
  title: string;
  description?: string;
  lotteryType: LotteryType;
  participationType: ParticipationType;
  startTime?: string;
  endTime?: string;
  maxParticipants?: number;
  state: LotteryState;
  participantCount?: number;
  prizes?: Prize[];
  winners?: Winner[];
  theme?: 'default' | 'slot-machine';
  /** 谢谢参与格子数量（大转盘/刮刮乐使用） */
  thankYouSlots?: number;
}

export interface ParticipateResult {
  success: boolean;
  message: string;
  token?: string;
  // 即时开奖结果
  isWinner?: boolean;
  prizeName?: string;
}

export interface StatusResult {
  participated: boolean;
  token?: string;
  isWinner?: boolean;
  prizeName?: string;
}
