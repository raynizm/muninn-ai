import { NavLink, Outlet } from "react-router-dom";
import { useAuth } from "../auth/AuthProvider";

export function Layout() {
  const { user, logout } = useAuth();
  return (
    <>
      <header className="topbar">
        <strong className="brand">Muninn</strong>
        <nav>
          <NavLink to="/" end>
            Recordings
          </NavLink>
          <NavLink to="/stats">Stats</NavLink>
          <NavLink to="/settings">Settings</NavLink>
        </nav>
        <span className="spacer" />
        <span className="muted">{user?.email}</span>
        <button onClick={() => void logout()}>Sign out</button>
      </header>
      <div className="content">
        <Outlet />
      </div>
    </>
  );
}
