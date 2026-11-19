export interface Comment {
  id: string;
  author: string;
  /** Exactly what the user typed in the comment box. */
  body: string;
  createdAt: string;
}
