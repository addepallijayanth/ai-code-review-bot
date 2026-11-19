import React from "react";
import type { Comment } from "../api/types";

interface Props {
  comments: Comment[];
}

export function CommentList({ comments }: Props) {
  if (comments.length === 0) {
    return <p className="empty">No comments yet.</p>;
  }
  return (
    <ul className="comments">
      {comments.map((comment) => (
        <li key={comment.id}>
          <strong>{comment.author}</strong>
          <div className="body" dangerouslySetInnerHTML={{ __html: comment.body }} />
        </li>
      ))}
    </ul>
  );
}
