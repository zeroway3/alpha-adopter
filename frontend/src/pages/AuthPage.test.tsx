import { render, screen, fireEvent } from "@testing-library/react";
import { describe, expect, it, vi, beforeEach } from "vitest";
import { AuthPage } from "./AuthPage";
import { AuthProvider } from "../auth/AuthContext";

beforeEach(() => {
  localStorage.clear();
});

describe("AuthPage", () => {
  it("기본값으로 로그인 탭이 활성화된 채 렌더된다", () => {
    render(
      <AuthProvider>
        <AuthPage />
      </AuthProvider>,
    );
    // 탭 버튼과 제출 버튼 둘 다 "로그인" 텍스트를 쓰므로 DOM 순서(탭이 먼저)로 구분한다
    const [loginTab] = screen.getAllByRole("button", { name: "로그인" });
    expect(loginTab).toHaveClass("active");
    expect(screen.getByPlaceholderText("you@example.com")).toBeInTheDocument();
  });

  it("회원가입 탭을 누르면 비밀번호 힌트가 8자 이상으로 바뀐다", () => {
    render(
      <AuthProvider>
        <AuthPage />
      </AuthProvider>,
    );
    fireEvent.click(screen.getByRole("button", { name: "회원가입" }));
    expect(screen.getByPlaceholderText("8자 이상")).toBeInTheDocument();
  });

  it("로그인 실패 시 서버 에러 메시지를 그대로 보여준다", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        ok: false,
        status: 401,
        json: async () => ({ detail: "이메일 또는 비밀번호가 올바르지 않습니다." }),
      }),
    );

    render(
      <AuthProvider>
        <AuthPage />
      </AuthProvider>,
    );
    fireEvent.change(screen.getByPlaceholderText("you@example.com"), { target: { value: "a@b.com" } });
    fireEvent.change(screen.getByPlaceholderText("비밀번호"), { target: { value: "wrongpass" } });
    const [, loginSubmit] = screen.getAllByRole("button", { name: "로그인" });
    fireEvent.click(loginSubmit);

    expect(await screen.findByText("이메일 또는 비밀번호가 올바르지 않습니다.")).toBeInTheDocument();
    vi.unstubAllGlobals();
  });
});
